"""Network-free extraction/catalogue/template tests. Clinical fixtures are synthetic."""
import json
import unittest
from pathlib import Path

from build_pack import extract_article, make_template


class PackTests(unittest.TestCase):
    def test_full_index_scope_and_unique_local_files(self):
        catalogue = json.loads((Path(__file__).parent / "catalogue.json").read_text())
        self.assertEqual({"Medicine", "Surgery"}, set(catalogue))
        medicine = [topic for topics in catalogue["Medicine"].values() for topic in topics]
        surgery = [topic for topics in catalogue["Surgery"].values() for topic in topics]
        self.assertEqual(165, len(medicine))
        self.assertEqual(135, len(surgery))
        self.assertEqual(300, len({topic["file"] for topic in medicine + surgery}))
        for topic in medicine + surgery:
            self.assertTrue(topic["url"].startswith("https://zerotofinals.com/"))
            self.assertNotIn("/", topic["file"])
            self.assertTrue(topic["file"].endswith(".md"))

    def test_selected_article_preserves_emphasis_tables_and_literals(self):
        html = '''<html><body><nav>Other disease menu</nav><h1>Fixture condition</h1>
        <div class="entry-content"><div id="content"><h3>Investigation</h3>
        <p>Preserve <strong>important concept</strong> and <em>emphasis</em>. Sample text for
        an artificial article that verifies boundaries and content preservation without including
        any real guideline. This paragraph supplies enough material for the completeness gate.</p>
        <table><tr><th>Test</th><th>Result</th></tr><tr><td>Fixture</td><td>&lt; 5</td></tr></table>
        <h3>Treatment</h3><p>Literal punctuation: 12.5 mg, 1–2 hours, ≥ 4. These are artificial values
        for testing extraction, not a real treatment recommendation.</p>
        <p>Now, head over to members.zerotofinals.com and test.</p><p>Other promotional text</p>
        </div></div><footer>Other conditions</footer></body></html>'''
        text, audit = extract_article(html, dict(title="Fixture condition", url="https://zerotofinals.com/medicine/example/"), "2026-10-14")
        self.assertIn("### Investigation", text)
        self.assertIn("**important concept**", text)
        self.assertIn("*emphasis*", text)
        self.assertIn("| Fixture | < 5 |", text)
        self.assertIn("12.5 mg, 1–2 hours, ≥ 4", text)
        self.assertNotIn("Other disease", text)
        self.assertNotIn("promotional", text)
        self.assertNotIn("Other conditions", text)
        self.assertEqual(["Investigation", "Treatment"], audit["headings"])

    def test_missing_article_fails_without_silent_page_fallback(self):
        with self.assertRaises(ValueError):
            extract_article("<h1>Blocked</h1><p>No article</p>", dict(title="Selected", url="https://zerotofinals.com/medicine/example/"), "now")

    def test_template_carries_catalogue_only_and_links_examiner(self):
        catalogue = json.loads((Path(__file__).parent / "catalogue.json").read_text())
        template = make_template(catalogue)
        self.assertEqual("zero-to-finals-osce.md", template["linkedSkill"])
        self.assertLess(len(json.dumps(template["answer"]).encode()), 80000)
        self.assertIn('type="range"', template["answer"]["html"])
        self.assertNotIn("fetch(", template["answer"]["jsFunctions"])
        self.assertNotIn("XMLHttpRequest", template["answer"]["jsFunctions"])
        self.assertIn("LOCAL_SOURCE_FILE", template["answer"]["jsFunctions"])


if __name__ == "__main__":
    unittest.main()
