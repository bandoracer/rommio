#!/usr/bin/env python3
"""Build pinned cartridge cores with the audited persistence extensions.

Source checkouts are disposable, per-target build inputs. Final manifests bind
source revision, patch and recipe to the exact binary; cache hits verify bytes.
"""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, sys
ROOT = pathlib.Path(__file__).resolve().parents[2]
PINS = pathlib.Path(__file__).with_name('pins.json')
RECIPE_BYTES = pathlib.Path(__file__).read_bytes()
def sha(data): return hashlib.sha256(data).hexdigest()
def run(args, **kw): subprocess.run([str(x) for x in args], check=True, stdout=sys.stderr, **kw)
def read(args): return subprocess.check_output([str(x) for x in args], text=True).strip()
def build(core, sdk, arch, ndk=None, cache=None):
    pin = json.loads(PINS.read_text())[core]; patch = PINS.parent/'patches'/f'{core}.patch'
    target = f'{sdk}-{arch}'; root = ROOT/'build/cartridge-cores'/core/target
    source = root/'source'; out = root/'output'; out.mkdir(parents=True,exist_ok=True)
    toolchain = read(['xcrun','clang','--version']) if sdk != 'android' else (ndk/'source.properties').read_text()
    if sdk == 'android' and '27.2.12479018' not in toolchain: raise RuntimeError('NDK 27.2.12479018 is required')
    extension_files=sorted((PINS.parent/'extensions').glob('rommio_file_bundle.*')) if core=='ppsspp' else []
    extension_bytes=b''.join(p.read_bytes() for p in extension_files)
    build_key = sha(PINS.read_bytes()+patch.read_bytes()+extension_bytes+RECIPE_BYTES+target.encode()+toolchain.encode())
    binary = out/(core+('.so' if sdk == 'android' else '.dylib')); manifest=out/'provenance.json'
    if manifest.exists() and binary.exists():
        previous=json.loads(manifest.read_text())
        if previous.get('buildKey')==build_key and previous['sha256']==sha(binary.read_bytes()): return binary
    # A fresh checkout prevents a prior target's objects or unrecorded edits entering this artifact.
    if source.exists(): shutil.rmtree(source)
    origin = cache/core if cache and (cache/core).exists() else pin['repository']
    run(['git','clone','--quiet','--no-checkout',origin,source])
    run(['git','-C',source,'checkout','--quiet','--detach',pin['revision']])
    assert read(['git','-C',source,'rev-parse','HEAD'])==pin['revision']
    if core=='ppsspp':
        modules=['libretro/libretro-common','ext/armips','ext/glslang','ext/SPIRV-Cross','ext/rapidjson','ext/zstd','ext/cpu_features','ext/rcheevos','ext/libchdr','ext/lua','ext/freetype','ext/nanosvg','ext/aemu_postoffice','ext/OpenXR-SDK','ext/miniupnp','ffmpeg','ext/libadrenotools']
        config=['-c','protocol.file.allow=always'] if cache else []
        if cache and (cache/core).exists():
            for module in modules:
                if (cache/core/module/'.git').exists():
                    # Gitmodule names mostly match paths; use the checked-in mapping for exceptions.
                    name=next((line.split()[0].removeprefix('submodule.').removesuffix('.path') for line in read(['git','-C',source,'config','-f','.gitmodules','--get-regexp','path']).splitlines() if line.split()[-1]==module),None)
                    if name: config+=['-c',f'submodule.{name}.url={cache/core/module}']
        run(['git',*config,'-C',source,'submodule','update','--init','--recursive','--depth','1','--jobs','8',*modules])
        for file in extension_files: shutil.copy2(file,source/'libretro'/file.name)
    run(['git','-C',source,'apply','--check',patch]);run(['git','-C',source,'apply',patch])
    cmake=os.environ.get('CMAKE','cmake'); build_dir=root/'cmake'
    if sdk != 'android':
        triple={'macosx':f'{arch}-apple-macos14.0','iphoneos':f'{arch}-apple-ios17.0','iphonesimulator':f'{arch}-apple-ios17.0-simulator'}[sdk]
        sysroot=read(['xcrun','--sdk',sdk,'--show-sdk-path'])
        cc=f'clang -target {triple} -isysroot {sysroot}'; cxx=f'clang++ -target {triple} -isysroot {sysroot}'
        if core=='mupen64plus_next_gles3':
            run(['make','-j8','-C',source, 'platform=osx' if sdk=='macosx' else 'platform=ios-arm64',f'ARCH={arch}',f'CC={cc}',f'CXX={cxx}',f'CC_AS={cc}','WITH_DYNAREC=','HAVE_PARALLEL_RSP=0','HAVE_PARALLEL_RDP=0','HAVE_THR_AL=1','HAVE_NEON=0','LLE=1','CPUFLAGS='])
            built=next(source.glob('*libretro*.dylib'))
        elif core=='gambatte':
            run(['make','-j8','-C',source,'platform=ios-arm64' if arch=='arm64' else 'platform=osx',f'CC={cc}',f'CXX={cxx}','PLATFORM_DEFINES='])
            built=next(source.glob('*libretro*.dylib'))
        elif core in ('snes9x','genesis_plus_gx'):
            run(['make','-j8','-C',source/('libretro' if core=='snes9x' else '.'),*(['-f','Makefile.libretro'] if core=='genesis_plus_gx' else []),'platform=osx','CROSS_COMPILE=1',f'LIBRETRO_APPLE_PLATFORM={triple}',f'LIBRETRO_APPLE_ISYSROOT={sysroot}','CC=clang','CXX=clang++'])
            built=source/('libretro/snes9x_libretro.dylib' if core=='snes9x' else 'genesis_plus_gx_libretro.dylib')
        elif core in ('stella','prosystem','handy'):
            make_root = source/'src/os/libretro' if core=='stella' else source
            run(['make','-j8','-C',make_root,'platform=osx','CROSS_COMPILE=1',f'LIBRETRO_APPLE_PLATFORM={triple}',f'LIBRETRO_APPLE_ISYSROOT={sysroot}','CC=clang','CXX=clang++'])
            built=make_root/(('stella2023' if core=='stella' else core)+'_libretro.dylib')
        elif core=='pcsx_rearmed':
            run(['make','-j8','-C',source,'-f','Makefile.libretro','platform=osx',f'CC={cc}',f'CXX={cxx}','DYNAREC=0','BUILTIN_GPU=peops'])
            built=source/'pcsx_rearmed_libretro.dylib'
        else:
            platform_flags=[f'-DCMAKE_OSX_SYSROOT={sdk}',f'-DCMAKE_OSX_ARCHITECTURES={arch}',f'-DCMAKE_OSX_DEPLOYMENT_TARGET={"14.0" if sdk=="macosx" else "17.0"}']
            if sdk!='macosx':platform_flags+=['-DCMAKE_SYSTEM_NAME=iOS']
    else:
        if core=='mupen64plus_next_gles3':
            host='darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
            llvm=ndk/f'toolchains/llvm/prebuilt/{host}/bin'
            triple={'arm64-v8a':'aarch64-linux-android','armeabi-v7a':'armv7a-linux-androideabi','x86':'i686-linux-android','x86_64':'x86_64-linux-android'}[arch]
            run(['make','-j8','-C',source,'platform=unix',f'ARCH={arch}',f'CC={llvm}/{triple}26-clang',f'CXX={llvm}/{triple}26-clang++','WITH_DYNAREC=','HAVE_PARALLEL_RSP=0','HAVE_PARALLEL_RDP=0','HAVE_THR_AL=1','HAVE_NEON=0','LLE=1','FORCE_GLES3=1','ANDROID=1','GL_LIB=-lGLESv3','CPUFLAGS=-DANDROID -DNO_ASM -DEGL_EGLEXT_PROTOTYPES','LDFLAGS=-shared -Wl,--version-script=libretro/link.T -Wl,--no-undefined -Wl,-z,max-page-size=16384 -llog -lm -lEGL'])
            built=source/'mupen64plus_next_libretro.so'
        elif core in ('gambatte','snes9x','genesis_plus_gx','pcsx_rearmed','stella','prosystem','handy'):
            jni=source/('libgambatte/libretro/jni/Android.mk' if core=='gambatte' else 'src/os/libretro/jni/Android.mk' if core=='stella' else 'jni/Android.mk' if core in ('pcsx_rearmed','prosystem','handy') else 'libretro/jni/Android.mk')
            run([ndk/'ndk-build','-j8','NDK_PROJECT_PATH=null',f'APP_BUILD_SCRIPT={jni}',f'APP_ABI={arch}','APP_PLATFORM=android-26','APP_OPTIM=release','APP_STL=c++_static',f'NDK_OUT={root}/objects',f'NDK_LIBS_OUT={root}/libs','APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true',*(['APP_CPPFLAGS=-fexceptions'] if core=='stella' else [])])
            built=next((root/'libs'/arch).glob('*.so'))
        else:
            platform_flags=[f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',f'-DANDROID_ABI={arch}','-DANDROID_PLATFORM=android-26','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON']
    if core in ('mgba','melonds_ds','ppsspp'):
        flags=['-DCMAKE_BUILD_TYPE=Release','-DBUILD_TESTING=OFF']
        if core=='mgba':
            flags+=['-DBUILD_LIBRETRO=ON']+[f'-D{x}=OFF' for x in ['BUILD_QT','BUILD_SDL','BUILD_GL','BUILD_GLES2','USE_FFMPEG','USE_PNG','USE_MINIZIP','USE_LIBZIP','USE_SQLITE3','USE_ELF','USE_LUA','USE_JSON_C','USE_FREETYPE','USE_LZMA','USE_DISCORD_RPC','USE_EPOXY','BUILD_TEST','BUILD_SUITE']]
        elif core=='ppsspp':
            flags+=['-DLIBRETRO=ON','-DHEADLESS=OFF','-DUNITTEST=OFF','-DATLAS_TOOL=OFF','-DUSE_FFMPEG=ON','-DBUILD_BUNDLED_FFMPEG=ON','-DUSE_DISCORD=OFF','-DUSE_MINIUPNPC=OFF','-DUSE_SYSTEM_LIBPNG=OFF','-DCMAKE_POSITION_INDEPENDENT_CODE=ON']
            if sdk != 'android': flags += [f'-DFORCED_CPU={arch}']
            if sdk in ('iphoneos','iphonesimulator'): flags+=['-DIOS=ON','-DIOS_APP_STORE=OFF','-DMOBILE_DEVICE=ON','-DUSING_GLES2=ON','-DARM_NO_VULKAN=ON']
        else:flags+=['-DENABLE_JIT=OFF','-DENABLE_OPENGL=OFF','-DBUILD_AS_SHARED_LIBRARY=ON']
        run([cmake,'-S',source,'-B',build_dir,*platform_flags,*flags]);run([cmake,'--build',build_dir,'-j8',*(['--target',core+'_libretro'] if core in ('mgba','ppsspp') else [])])
        built=next(build_dir.rglob(('ppsspp_libretro'+('_android' if sdk=='android' else '') if core=='ppsspp' else 'mgba_libretro' if core=='mgba' else ('melondsds_libretro_android' if sdk=='android' else 'melondsds_libretro'))+('.so' if sdk=='android' else '.dylib')))
    if core=='ppsspp':
        asset_root=out/'system'/'PPSSPP';asset_root.mkdir(parents=True,exist_ok=True)
        names=['compat.ini','compatvr.ini','knownfuncs.ini','langregion.ini','ppge_atlas.meta','ppge_atlas.zim','font_atlas.meta','font_atlas.zim','asciifont_atlas.meta','asciifont_atlas.zim']
        files=[source/'assets'/name for name in names]+sorted((source/'assets/flash0').rglob('*'))+sorted((source/'assets/vfpu').rglob('*'))
        records={}
        for file in files:
            if not file.is_file(): continue
            relative=file.relative_to(source/'assets');dest=asset_root/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest)
            records['PPSSPP/'+relative.as_posix()]={'sha256':sha(dest.read_bytes()),'sizeBytes':dest.stat().st_size}
        (out/'system-manifest.json').write_text(json.dumps(records,indent=2)+'\n')
    shutil.copy2(built,binary)
    if sdk!='android':
        run(['install_name_tool','-id',f'@rpath/{core}.dylib',binary]);run(['codesign','--force','--sign','-','--timestamp=none',binary])
        symbols=read(['nm','-gU',binary])
    else:
        host='darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
        symbols=read([ndk/f'toolchains/llvm/prebuilt/{host}/bin/llvm-nm','-D',binary])
    for symbol in ['retro_rommio_persistence_descriptor','retro_rommio_memory_restored','retro_get_memory_data']:
        if symbol not in symbols:raise RuntimeError('Missing export '+symbol)
    license_file = source/('COPYING' if core=='gambatte' else 'LICENSE')
    if core=='genesis_plus_gx': license_file=source/'LICENSE.txt'
    if core=='pcsx_rearmed': license_file=source/'COPYING'
    if core=='mupen64plus_next_gles3': license_file=source/'LICENSE'
    if core=='ppsspp': license_file=source/'LICENSE.TXT'
    if core=='melonds_ds': license_file=next(build_dir.rglob('melondsds-LICENSE.txt'))
    if core=='stella': license_file=source/'License.txt'
    if core=='prosystem': license_file=source/'License.txt'
    if core=='handy': license_file=source/'lynx/license.txt'
    shutil.copy2(license_file,out/'LICENSE.txt')
    record=dict(schemaVersion=1,runtimeId=core,sourceRevision=pin['revision'],sourceManifestSha256=sha(PINS.read_bytes()),patchSha256=sha(patch.read_bytes()),extensionSha256=sha(extension_bytes),recipeSha256=sha(RECIPE_BYTES),persistenceExtension=pin['persistenceExtension'],sdk=sdk,architecture=arch,sha256=sha(binary.read_bytes()),buildKey=build_key)
    manifest.write_text(json.dumps(record,indent=2)+'\n');return binary
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--core',choices=list(json.loads(PINS.read_text())),required=True);p.add_argument('--sdk',choices=['macosx','iphonesimulator','iphoneos','android'],required=True);p.add_argument('--arch',default='arm64');p.add_argument('--ndk',type=pathlib.Path);p.add_argument('--source-cache',type=pathlib.Path,default=os.environ.get('ROMMIO_CORE_SOURCE_CACHE'))
    a=p.parse_args();print(build(a.core,a.sdk,a.arch,a.ndk,a.source_cache))
