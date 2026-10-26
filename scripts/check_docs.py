#!/usr/bin/env python3
"""Check public Markdown links/fragments, short README and registered module guide coverage."""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re
import sys


def without_fences(text):
    return re.sub(r'^\s*(`{3,}|~{3,})[^\n]*\n.*?^\s*\1\s*$', '', text, flags=re.M | re.S)


def slug(text):
    text = re.sub(r'<[^>]+>', '', text).strip().lower()
    text = re.sub(r'[^\w\- ]', '', text)
    return text.replace(' ', '-')


def anchors(text):
    seen, result = {}, set()
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', without_fences(text), re.M):
        name = slug(heading)
        count = seen.get(name, 0)
        result.add(name + (f'-{count}' if count else ''))
        seen[name] = count + 1
    result.update(re.findall(r'<a\s+(?:name|id)=[\'"]([^\'"]+)', text))
    return result


def check_tree(root):
    root = Path(root).resolve()
    errors = []
    readme = root / 'README.md'
    if not readme.is_file():
        return ['README.md is missing']
    if len(readme.read_text().splitlines()) > 100:
        errors.append('README.md must stay within 100 lines; move detail to module guides')
    public = [readme]
    if (root / 'CONTRIBUTING.md').exists():
        public.append(root / 'CONTRIBUTING.md')
    public += sorted((root / 'docs').glob('*.md'))
    for folder in ['modules', 'guides']:
        public += sorted((root / 'docs' / folder).rglob('*.md'))
    for path in public:
        for match in re.finditer(r'(?<!!)\[[^\]\n]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', without_fences(path.read_text())):
            link = match.group(1).strip('<>')
            parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc:
                continue
            target = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path
            if not target.exists():
                errors.append(f'{path.relative_to(root)}: missing target {link}')
            elif parsed.fragment and target.suffix == '.md' and unquote(parsed.fragment) not in anchors(target.read_text()):
                errors.append(f'{path.relative_to(root)}: missing heading {link}')
    registry = root / 'src/main/java/dev/tntcoating/TntCoatingAddon.java'
    if not registry.is_file():
        errors.append('Cannot check module coverage: TntCoatingAddon.java missing')
        return errors
    source = registry.read_text()
    classes = re.findall(r'ordered\.add\(new (\w+)\(', source)
    upgrades = re.findall(r'upgraded\(ordered, modules\.get\((\w+)\.class\)', source)
    index = root / 'docs/README.md'
    index_text = index.read_text() if index.is_file() else ''
    for name in classes + upgrades:
        module = root / f'src/main/java/dev/tntcoating/modules/{name}.java'
        if module.is_file():
            constructor = re.search(r'super\(\s*(?:TntCoatingAddon\.CATEGORY,\s*)?"([a-z][a-z0-9-]+)"', module.read_text())
            if not constructor:
                errors.append(f'Cannot identify module name for {name}')
                continue
            identifier = constructor.group(1)
        else:
            identifier = re.sub(r'([a-z0-9])([A-Z])', r'\1-\2', re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1-\2', name)).lower()
        guide = f'modules/{identifier}.md'
        if not (root / 'docs' / guide).is_file():
            errors.append(f'Registered module {identifier} has no guide: docs/{guide}')
        elif f']({guide})' not in index_text:
            errors.append(f'Registered module {identifier} is not indexed in docs/README.md')
    return errors


if __name__ == '__main__':
    tree = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    failures = check_tree(tree)
    if failures:
        print('\n'.join(failures))
        sys.exit(1)
    print('PASS: README length, local documentation links/fragments and module guide coverage')
