#!/usr/bin/env python3
"""Inspect the final app product and emit only verified, signed core frameworks."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True)


def signature_details(path: pathlib.Path) -> tuple[str | None, list[str]]:
    output = run('codesign', '-dv', '--verbose=4', str(path)).stderr
    fields = [line.strip() for line in output.splitlines()]
    team = next((line.partition('=')[2] for line in fields if line.startswith('TeamIdentifier=')), None)
    authorities = [line.partition('=')[2] for line in fields if line.startswith('Authority=')]
    return team, authorities


def verify(app: pathlib.Path, distribution: bool) -> list[dict]:
    if not app.is_dir():
        raise ValueError(f'Missing app product: {app}')
    debug_dylibs = {'Rommio.debug.dylib', '__preview.dylib'} if not distribution else set()
    loose = sorted(path.relative_to(app).as_posix() for path in app.rglob('*.dylib')
                   if 'Frameworks/' not in path.relative_to(app).as_posix()
                   and path.relative_to(app).as_posix() not in debug_dylibs)
    if loose:
        raise ValueError(f'Executable libraries outside embedded frameworks: {loose}')
    app_team = None
    if distribution:
        app_team, app_authorities = signature_details(app)
        if not app_team or app_team == 'not set' or not any(authority.startswith('Apple Distribution:') for authority in app_authorities):
            raise ValueError('App product is not signed by an Apple Distribution identity')
        run('codesign', '--verify', '--strict', '--deep', str(app))
    entries = json.loads((app/'CoreLicenses.json').read_text())
    verified = []
    for entry in entries:
        runtime = entry['id']
        relative = f'Frameworks/{runtime}.framework/{runtime}'
        if entry['binary_path'] != relative:
            raise ValueError(f'{runtime}: license inventory does not name its framework')
        binary = app/relative
        if not binary.is_file() or not (app/entry['license_path']).is_file():
            raise ValueError(f'{runtime}: framework binary or license missing')
        framework = binary.parent
        run('codesign', '--verify', '--strict', str(framework))
        team, authorities = signature_details(framework)
        if distribution and (team != app_team or not any(authority.startswith('Apple Distribution:') for authority in authorities)):
            raise ValueError(f'{runtime}: framework is not Apple Distribution signed by the app team')
        architectures = run('lipo', '-archs', str(binary)).stdout.split()
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        provenance = app/f'{runtime}Provenance.json'
        if provenance.exists():
            record = json.loads(provenance.read_text())
            if record.get('runtimeId') != runtime or record.get('sha256') != digest:
                raise ValueError(f'{runtime}: final framework digest does not match provenance')
        verified.append({'runtimeId': runtime, 'binaryPath': relative, 'sha256': digest,
                         'architectures': architectures,
                         'teamSigned': bool(team and team != 'not set')})
    return verified


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=pathlib.Path)
    parser.add_argument('--distribution', action='store_true')
    parser.add_argument('--output', type=pathlib.Path)
    options = parser.parse_args()
    try:
        inventory = verify(options.app, options.distribution)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
    result = json.dumps(inventory, indent=2)+'\n'
    if options.output:
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(result)
    else:
        print(result, end='')
