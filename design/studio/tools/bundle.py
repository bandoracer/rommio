#!/usr/bin/env python3
"""Inline index.html's stylesheets and scripts into one self-contained page.

    python3 design/studio/tools/bundle.py [out.html] [--fragment]

--fragment drops the <!doctype>/<html>/<head>/<body> wrapper (for hosts that
supply their own skeleton, e.g. a claude.ai Artifact). Only local files are
inlined; Google Fonts are still fetched at runtime by core/studio.js.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    fragment = '--fragment' in sys.argv
    out = Path(args[0]) if args else ROOT / 'dist' / 'rommio-ui-studio.html'
    html = (ROOT / 'index.html').read_text()

    def css(m: re.Match) -> str:
        path = ROOT / m.group(1)
        return f'<style>/* {m.group(1)} */\n{path.read_text()}</style>' if path.exists() else ''

    def js(m: re.Match) -> str:
        path = ROOT / m.group(1)
        # "</script" inside a script body would end the tag early.
        body = path.read_text().replace('</script', '<\\/script') if path.exists() else ''
        return f'<script>/* {m.group(1)} */\n{body}</script>' if body else ''

    html = re.sub(r'<link rel="stylesheet" href="((?:core|versions)/[^"]+)">', css, html)
    html = re.sub(r'<script src="((?:core|versions)/[^"]+)"></script>', js, html)

    if fragment:
        title = re.search(r'<title>.*?</title>', html).group(0)
        head = re.search(r'<head>(.*?)</head>', html, re.S).group(1)
        head = re.sub(r'<meta[^>]*>\s*|<title>.*?</title>\s*', '', head)
        body = re.search(r'<body>(.*?)</body>', html, re.S).group(1)
        html = f'{title}\n{head}\n{body}'

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html)
    print(f'{out} ({out.stat().st_size / 1024:.0f} KB)')


if __name__ == '__main__':
    main()
