"""Regression tests for the release documentation validator."""
import tempfile
import unittest
from pathlib import Path
from check_docs import check_tree

class DocumentationChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        files = {
            'README.md': '# Comet\n\n[Foo](docs/modules/foo.md#usage)\n',
            'docs/README.md': '# Guides\n\n[Foo](modules/foo.md)\n',
            'docs/modules/foo.md': '# Foo\n\n## Usage\n\n[Home](../../README.md)\n',
            'src/main/java/dev/tntcoating/TntCoatingAddon.java': 'ordered.add(new Foo());',
            'src/main/java/dev/tntcoating/modules/Foo.java': 'super(TntCoatingAddon.CATEGORY, "foo", "A module");',
        }
        for name, text in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    def test_valid_links_and_registered_module_guide(self):
        self.assertEqual([], check_tree(self.root))

    def test_missing_file_and_fragment_are_rejected(self):
        (self.root / 'README.md').write_text('[Absent](missing.md)\n[Heading](docs/modules/foo.md#missing)\n')
        errors = check_tree(self.root)
        self.assertTrue(any('missing.md' in e for e in errors))
        self.assertTrue(any('#missing' in e for e in errors))

    def test_module_guide_and_index_are_required(self):
        (self.root / 'docs/modules/foo.md').unlink()
        self.assertTrue(any('module foo' in e for e in check_tree(self.root)))
        (self.root / 'docs/modules/foo.md').write_text('# Foo\n## Usage\n')
        (self.root / 'docs/README.md').write_text('# Guides\n')
        self.assertTrue(any('not indexed' in e for e in check_tree(self.root)))

    def test_fenced_examples_are_not_links_and_readme_stays_short(self):
        (self.root / 'docs/modules/foo.md').write_text('# Foo\n## Usage\n```md\n[Example](not-real.md)\n```\n')
        self.assertEqual([], check_tree(self.root))
        (self.root / 'README.md').write_text('\n' * 101)
        self.assertTrue(any('100 lines' in e for e in check_tree(self.root)))

if __name__ == '__main__':
    unittest.main()
