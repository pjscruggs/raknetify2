"""Check packaged language sources against Minecraft's locale inventory and message arguments."""
from collections import Counter
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
CATALOGS = ROOT / 'common/src/main/resources/assets/raknetify/lang'
INVENTORY = Path(__file__).with_name('minecraft-locales.json')
ARGUMENT = re.compile(r'%([1-9][0-9]*)\$s')
LITERALS = {
    'raknetify.console.host_string': ('host',),
    'raknetify.console.voice_enabled': ('enabled', 'true', 'false'),
    'raknetify.console.ordered_required': ('raknet;',),
    'raknetify.console.leak_invalid': ('io.netty.leakDetection.level', 'SIMPLE'),
    'raknetify.console.map_read': ('channelMappings.json',),
    'raknetify.console.map_write': ('channelMappings.json',),
}


def unique(pairs):
    """Reject duplicate keys before a JSON reader can silently overwrite a message."""
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError('Duplicate language key')
    return result


def messages(path):
    """Read strict UTF-8 so a byte-order mark or damaged encoding fails the check."""
    return json.loads(path.read_bytes().decode('utf-8'), object_pairs_hook=unique)


def signature(text):
    """Allow translators to move positional arguments while keeping each value present."""
    if not isinstance(text, str) or not text.strip() or '\ufffd' in text:
        raise ValueError('Missing or damaged message')
    # JSON escape sequences can introduce lone surrogates even in a valid UTF-8 file.
    text.encode('utf-8')
    if re.search(r'[\x00-\x1f\x7f-\x9f]|Ãƒ|Ã¯|â€™|ðŸ|ÄŤ|Ĺˇ|\b(?:TODO|FIXME)\b', text):
        raise ValueError('Damaged or unfinished translation')
    if '%' in ARGUMENT.sub('', text).replace('%%', ''):
        raise ValueError('Invalid format specifier')
    return Counter(ARGUMENT.findall(text))


def check_operator_keys(schema):
    """Check the names used by production code against the packaged operator catalog."""
    used = set()
    for source in ROOT.glob('*/src/main/java/**/*.java'):
        java = source.read_text(encoding='utf-8')
        used.update('raknetify.console.' + key for key in re.findall(
            r'ConsoleMessages\.text\(\s*"([^"]+)"', java))
        used.update('raknetify.console.' + key for key in re.findall(
            r'ConsoleMessages\.transport\(\s*[^,]+,\s*"([^"]+)"', java))
    if used - schema.keys():
        raise ValueError(f'Missing operator messages: {sorted(used - schema.keys())}')


def check_literals(locale, data):
    """Keep instructions that players and operators can copy valid even in novelty locales."""
    for key, tokens in LITERALS.items():
        for token in tokens:
            if token not in data[key]:
                raise ValueError(f'{locale}: {key} must preserve {token!r}')


def check_english_fallback(locale, data, english):
    """Detect whole operator sentences accidentally pasted from the reference catalog."""
    if locale.startswith('en') or locale == 'lol_us':
        return
    for key, value in data.items():
        if key.startswith('raknetify.console.') and value == english[key]:
            words = re.findall(r'\b[A-Za-z]{2,}\b', ARGUMENT.sub('', value))
            if len(words) >= 5:
                raise ValueError(f'{locale}: English operator sentence in {key}')


def main():
    """Require every supported locale to supply the complete resource-key and argument schema."""
    inventory = set(messages(INVENTORY)['locales'])
    actual = {path.stem for path in CATALOGS.glob('*.json')}
    if inventory != actual:
        raise ValueError(f'Minecraft language inventory differs: {sorted(inventory ^ actual)}')
    english = messages(CATALOGS / 'en_us.json')
    schema = {key: signature(text) for key, text in english.items()}
    check_operator_keys(schema)
    for locale in sorted(inventory):
        data = messages(CATALOGS / (locale + '.json'))
        if data.keys() != schema.keys():
            raise ValueError(f'{locale}: mismatched translation keys')
        check_literals(locale, data)
        check_english_fallback(locale, data, english)
        for key, text in data.items():
            try:
                arguments = signature(text)
            except ValueError as error:
                raise ValueError(f'{locale}: {key}: {error}') from error
            if arguments != schema[key]:
                raise ValueError(f'{locale}: incorrect arguments for {key}')
    print(f'Checked {len(inventory)} languages and {len(schema)} messages per language.')


if __name__ == '__main__':
    main()
