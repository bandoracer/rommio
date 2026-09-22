#!/usr/bin/env python3
"""Build the same cartridge pins for every supported Android process ABI."""
import argparse,importlib.util,json,pathlib,shutil,os,subprocess,sys
spec=importlib.util.spec_from_file_location('core_build',pathlib.Path(__file__).with_name('build.py'));build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--ndk',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
    cache=os.environ.get('ROMMIO_CORE_SOURCE_CACHE');cache=pathlib.Path(cache) if cache else None
    for runtime in json.loads(build.PINS.read_text()):
        artifacts=[]
        for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
            binary=build.build(runtime,'android',abi,a.ndk,cache);name='melondsds' if runtime=='melonds_ds' else runtime
            relative=f'cores/{runtime}/{abi}/lib{name}_libretro_android.so';dest=a.output/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(binary,dest)
            host='darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
            subprocess.run([str(a.ndk/f'toolchains/llvm/prebuilt/{host}/bin/llvm-strip'),'--strip-unneeded',str(dest)],check=True)
            artifacts.append(dict(abi=abi,assetPath=relative,sha256=build.sha(dest.read_bytes()),sizeBytes=dest.stat().st_size))
        pin=json.loads(binary.with_name('provenance.json').read_text());pin.update(packagingRecipeSha256=build.sha(pathlib.Path(__file__).read_bytes()),ndkVersion='27.2.12479018',androidApi=26,artifacts=artifacts)
        directory=a.output/'cores'/runtime
        if binary.with_name('system-manifest.json').exists():
            shutil.copytree(binary.with_name('system'),directory/'system',dirs_exist_ok=True)
            shutil.copy2(binary.with_name('system-manifest.json'),directory/'system-manifest.json')
        (directory/'manifest.json').write_text(json.dumps(pin,indent=2)+'\n');shutil.copy2(binary.with_name('LICENSE.txt'),directory/'LICENSE.txt')
if __name__=='__main__':main()
