#!/usr/bin/env python3
"""Install signed cartridge cores and provenance into a normal Xcode product."""
import importlib.util,json,os,pathlib,shutil
spec=importlib.util.spec_from_file_location('core_build',pathlib.Path(__file__).with_name('build.py'));build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)
framework_spec=importlib.util.spec_from_file_location('embed_core_framework',pathlib.Path(__file__).resolve().parents[1]/'ios/embed_core_framework.py');framework=importlib.util.module_from_spec(framework_spec);framework_spec.loader.exec_module(framework)
def main():
    root=pathlib.Path(os.environ['TARGET_BUILD_DIR'])/os.environ['UNLOCALIZED_RESOURCES_FOLDER_PATH']
    sdk=os.environ['PLATFORM_NAME'];arch=os.environ.get('CURRENT_ARCH','arm64')
    if arch=='undefined_arch':arch=os.environ.get('NATIVE_ARCH_ACTUAL','arm64')
    if sdk=='iphoneos':arch='arm64'
    manifest=root/'CoreLicenses.json';licenses=json.loads(manifest.read_text()) if manifest.exists() else []
    cache=os.environ.get('ROMMIO_CORE_SOURCE_CACHE');cache=pathlib.Path(cache) if cache else None
    identity=os.environ.get('EXPANDED_CODE_SIGN_IDENTITY') or '-'
    if sdk=='iphoneos' and os.environ.get('CONFIGURATION')=='Release' and identity=='-':
        raise RuntimeError('A distribution signing identity is required for a device Release build')
    for runtime in json.loads(build.PINS.read_text()):
        for stale in (root/f'{runtime}.dylib', root/'Cores'/f'{runtime}.dylib'):
            stale.unlink(missing_ok=True)
        binary=build.build(runtime,sdk,arch,cache=cache)
        dest,digest=framework.embed(binary,root,runtime,identity)
        if binary.with_name('system-manifest.json').exists():
            assets=root/'Cores/system'/runtime;assets.mkdir(parents=True,exist_ok=True)
            shutil.copytree(binary.with_name('system'),assets/'system',dirs_exist_ok=True)
            shutil.copy2(binary.with_name('system-manifest.json'),assets/'system-manifest.json')
        pin=json.loads(binary.with_name('provenance.json').read_text());pin['sha256']=digest;(root/f'{runtime}Provenance.json').write_text(json.dumps(pin,indent=2)+'\n')
        license_dest=root/'Cores/licenses'/f'{runtime}.LICENSE.txt';license_dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(binary.with_name('LICENSE.txt'),license_dest)
        licenses=[x for x in licenses if x['id']!=runtime]+[{'id':runtime,'binary_path':f'Frameworks/{runtime}.framework/{runtime}','license_path':f'Cores/licenses/{runtime}.LICENSE.txt'}]
    fbneo=pathlib.Path(__file__).resolve().parents[2]/'ios/App/Resources/Cores/fbneo.dylib'
    if fbneo.exists():
        for stale in (root/'fbneo.dylib', root/'Cores/fbneo.dylib'):
            stale.unlink(missing_ok=True)
        framework.embed(fbneo,root,'fbneo',identity)
        license_dest=root/'Cores/licenses/fbneo.LICENSE.txt';license_dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(fbneo.parent/'licenses/fbneo.LICENSE.txt',license_dest)
        licenses=[{**entry,'binary_path':'Frameworks/fbneo.framework/fbneo','license_path':'Cores/licenses/fbneo.LICENSE.txt'} if entry['id']=='fbneo' else entry for entry in licenses]
    include_candidate=sdk in ('iphonesimulator','iphoneos') and os.environ.get('CONFIGURATION')=='Debug' and os.environ.get('ROMMIO_INCLUDE_AZAHAR_CANDIDATE')=='1'
    if include_candidate:
        candidate_spec=importlib.util.spec_from_file_location('azahar_candidate',pathlib.Path(__file__).with_name('build_azahar_candidate.py'))
        candidate=importlib.util.module_from_spec(candidate_spec);candidate_spec.loader.exec_module(candidate)
        source_cache=os.environ.get('ROMMIO_AZAHAR_SOURCE_CACHE')
        binary=candidate.build(sdk,arch,pathlib.Path(source_cache) if source_cache else None)
        _,digest=framework.embed(binary,root,'azahar',identity)
        provenance=json.loads(binary.with_name('provenance.json').read_text());provenance['sha256']=digest
        (root/'azaharProvenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
        license_dest=root/'Cores/licenses/azahar.LICENSE.txt';license_dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(binary.with_name('LICENSE.txt'),license_dest)
        licenses=[x for x in licenses if x['id']!='azahar']+[{'id':'azahar','binary_path':'Frameworks/azahar.framework/azahar','license_path':'Cores/licenses/azahar.LICENSE.txt'}]
    else:
        shutil.rmtree(root/'Frameworks/azahar.framework',ignore_errors=True)
        (root/'azaharProvenance.json').unlink(missing_ok=True)
        (root/'Cores/licenses/azahar.LICENSE.txt').unlink(missing_ok=True)
        licenses=[x for x in licenses if x['id']!='azahar']
    manifest.write_text(json.dumps(licenses,indent=2)+'\n')
if __name__=='__main__': main()
