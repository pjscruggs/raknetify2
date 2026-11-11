"""Check packaged language sources against Minecraft's locale inventory and message arguments."""
from collections import Counter
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
CATALOGS = ROOT / 'common/src/main/resources/assets/raknetify/lang'
INVENTORY = Path(__file__).with_name('minecraft-locales.json')
ARGUMENT = re.compile(r'%([1-9][0-9]*)\$s')


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
    if re.search(r'[\x80-\x9f]|Ãƒ|Ã¯|â€™|ðŸ|ÄŤ|Ĺˇ|\b(?:TODO|FIXME)\b', text):
        raise ValueError('Damaged or unfinished translation')
    if '%' in ARGUMENT.sub('', text).replace('%%', ''):
        raise ValueError('Invalid format specifier')
    return Counter(ARGUMENT.findall(text))


def main():
    """Require every supported locale to supply the complete resource-key and argument schema."""
    inventory = set(messages(INVENTORY)['locales'])
    actual = {path.stem for path in CATALOGS.glob('*.json')}
    if inventory != actual:
        raise ValueError(f'Minecraft language inventory differs: {sorted(inventory ^ actual)}')
    schema = {key: signature(text) for key, text in messages(CATALOGS / 'en_us.json').items()}
    for locale in sorted(inventory):
        data = messages(CATALOGS / (locale + '.json'))
        if data.keys() != schema.keys():
            raise ValueError(f'{locale}: mismatched translation keys')
        for key, text in data.items():
            if signature(text) != schema[key]:
                raise ValueError(f'{locale}: incorrect arguments for {key}')
    print(f'Checked {len(inventory)} languages and {len(schema)} messages per language.')


if __name__ == '__main__':
    main()
