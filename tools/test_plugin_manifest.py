"""Check release overrides and preservation of the development manifest."""
import json
from pathlib import Path
import tempfile
import unittest

from plugin_manifest import build_manifest


class BuildManifestTest(unittest.TestCase):
    def test_project_declares_upstream_screensaver_discovery_contract(self):
        manifest = json.loads((Path(__file__).resolve().parents[1] / 'kiosk-satellite-plugin.json').read_text())
        self.assertEqual(manifest['schemaVersion'], 1)
        self.assertEqual(manifest['apiVersion'], 1)
        self.assertIn('screensaver', manifest['capabilities'])
        self.assertIn('host.read', manifest['capabilities'])
        self.assertEqual(
            manifest['entryClass'],
            'com.darkmatter.kiosk.applealbum.AppleSharedAlbumPlugin',
        )

    def test_project_duration_setting_matches_runtime_contract(self):
        manifest = json.loads((Path(__file__).resolve().parents[1] / 'kiosk-satellite-plugin.json').read_text())
        duration = next(setting for setting in manifest['settings'] if setting['key'] == 'intervalSeconds')
        self.assertEqual(
            {key: duration[key] for key in ('type', 'default', 'min', 'max', 'step', 'unit')},
            {'type': 'number', 'default': 30, 'min': 5, 'max': 3600, 'step': 5, 'unit': 's'},
        )

    def test_overlay_defaults_positions_and_privacy_contract(self):
        manifest = json.loads((Path(__file__).resolve().parents[1] / 'kiosk-satellite-plugin.json').read_text())
        self.assertEqual(manifest['version'], '0.1.7')
        settings = {setting['key']: setting for setting in manifest['settings']}
        self.assertEqual(settings['showClock']['default'], False)
        self.assertEqual(settings['showCurrentDate']['default'], False)
        self.assertEqual(settings['showPhotoDate']['default'], False)
        self.assertEqual(settings['showLocation']['default'], False)
        self.assertEqual(settings['overlayPixelShift']['default'], False)
        self.assertIn('10 pixels once per minute', settings['overlayPixelShift']['description'])
        self.assertIn('photo does not move', settings['overlayPixelShift']['description'])
        self.assertEqual(settings['overlayPosition']['default'], 'Top right')
        self.assertEqual(
            settings['overlayPosition']['options'],
            ['Top left', 'Top right', 'Bottom left', 'Bottom right'],
        )
        self.assertEqual(settings['clock24Hour']['default'], False)
        self.assertEqual(settings['clockSeconds']['default'], False)
        self.assertEqual(settings['overlayColor']['default'], '#FAFAFA')
        self.assertEqual(
            {key: settings['overlayScale'][key] for key in ('default', 'min', 'max', 'step', 'unit')},
            {'default': 100, 'min': 50, 'max': 150, 'step': 5, 'unit': '%'},
        )
        self.assertIn('No geocoding', settings['showLocation']['description'])

    def test_release_version_overrides_source_without_editing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'kiosk-satellite-plugin.json'
            original = b'{"id":"demo","version":"1.0.1","apiVersion":1,"description":"Demo","settings":[]}'
            path.write_bytes(original)
            self.assertEqual(build_manifest(path), original)
            release = json.loads(build_manifest(path, '1.2.0'))
            self.assertEqual(release, {**json.loads(original), 'version': '1.2.0'})
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(json.loads(build_manifest(path, '1.3.0-beta.1'))['version'], '1.3.0-beta.1')

    def test_invalid_versions_cannot_become_package_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'kiosk-satellite-plugin.json'
            path.write_text('{"version":"1.0.1"}')
            for version in ['', 'v1.2.0', '1.2', '../1.2.0', '1.2.0/other', '1.2.0\n', '1.2.0-' + 'a' * 40]:
                with self.subTest(version=version), self.assertRaises(ValueError):
                    build_manifest(path, version)


if __name__ == '__main__':
    unittest.main()
