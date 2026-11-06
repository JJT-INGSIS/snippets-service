"""Local SNI-64 queries and actual default assessment; does not certify the US15 worker."""
import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
parser.add_argument('--phase', choices=['healthy', 'processor-down', 'storage-down', 'recovered'], required=True)
parser.add_argument('--evidence', type=Path, required=True)
parser.add_argument('--fault-control', type=Path, help='Optional control file of the local real-provider fault proxy')
args = parser.parse_args()
assert urllib.parse.urlsplit(args.base_url).hostname in ('localhost', '127.0.0.1', '::1'), 'Use an isolated local dev environment'


def api(actor, method, path, body=None, expected=200, headers=None):
    request = urllib.request.Request(
        args.base_url.rstrip('/') + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'X-Dev-Actor-Id': actor, 'Content-Type': 'application/json', **(headers or {})},
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status, value = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, value = error.code, json.load(error)
    assert status == expected, (method, path, status, value)
    return value


def rules(actor, selection):
    return api(actor, 'PUT', '/linting-rules/printscript/1.1', {'rules': selection})


def create(actor, prefix, label, code):
    payload = {'name': prefix + label, 'description': None, 'language': 'printscript', 'version': '1.1', 'code': code}
    return api(actor, 'POST', '/snippets', payload, expected=201, headers={'Idempotency-Key': str(uuid.uuid4())})['id']


def listing(record, extra=''):
    query = urllib.parse.urlencode({'name': record['prefix'], 'sort': 'lint', 'page_size': 2})
    return api('dev-ana', 'GET', '/snippets?' + query + extra)


if args.phase == 'healthy':
    prefix = 'SNI64-' + str(uuid.uuid4()) + '-'
    selection = {'mandatory-variable-or-literal-in-println': True}
    owner_rules = rules('dev-thiago', selection)
    bad = create('dev-thiago', prefix, 'bad', 'println(1+2);')
    good = create('dev-thiago', prefix, 'good', 'println(1);')
    rules('dev-ana', {})
    pending = create('dev-ana', prefix, 'superseded', 'println(1+2);')
    rules('dev-ana', selection)
    defaults = api('dev-juan', 'GET', '/linting-rules/printscript/1.1')
    assert any(rule['value'] is not None and rule['value'] is not False for rule in defaults['rules'])
    default_assessed = create('dev-juan', prefix, 'defaults', 'println(1);')
    outsider = create('dev-bruno', prefix, 'unshared', 'println(1);')
    for owner, snippet in [('dev-thiago', bad), ('dev-thiago', good), ('dev-juan', default_assessed)]:
        api(owner, 'PUT', '/snippets/' + snippet + '/shares/dev-ana', expected=201)
    expected = {'compliant': [good, default_assessed], 'not-compliant': [bad], 'pending': [pending]}
    if args.fault_control:
        marker = prefix + 'lint-only-failure'
        args.fault_control.write_text(json.dumps({'lintFailure': marker}))
        try:
            failed = create('dev-juan', prefix, 'failed', 'println("' + marker + '");')
        finally:
            args.fault_control.write_text('{}')
        api('dev-juan', 'PUT', '/snippets/' + failed + '/shares/dev-ana', expected=201)
        expected['failed'] = [failed]
    count = sum(len(items) for items in expected.values())
    record = {'prefix': prefix, 'ids': expected, 'outsider': outsider, 'expectedCount': count}
    pages = [listing(record, '&page=' + str(page)) for page in range(3)]
    assert [page['count'] for page in pages] == [count, count, count]
    rows = [row for page in pages for row in page['snippets']]
    ordered = [snippet for status in sorted(expected) for snippet in sorted(expected[status])]
    assert [row['id'] for row in rows] == ordered, rows
    assert all('code' not in row for row in rows)
    for status, snippets in expected.items():
        page = listing(record, '&lint=' + status + '&page=0')
        assert page['count'] == len(snippets) and [row['id'] for row in page['snippets']] == sorted(snippets)
        for snippet in snippets:
            detail = api('dev-ana', 'GET', '/snippets/' + snippet)
            assert detail['lint']['status'] == status
            if status == 'pending':
                assert detail['lint']['reason'] == 'SUPERSEDED' and detail['lint']['diagnostics'] == []
                assert detail['lint']['codeHash'] is None
            if status == 'not-compliant':
                assert detail['lint']['diagnostics'] and detail['capabilities']['modify'] is False
            if status == 'failed':
                assert detail['lint']['reason'] and not detail['lint']['diagnostics']
            if snippet == default_assessed:
                assert detail['lint']['rulesRevision'] == defaults['revision']
    api('dev-bruno', 'GET', '/snippets/' + bad, expected=404)
    foreign = api('dev-bruno', 'GET', '/snippets?' + urllib.parse.urlencode({'name': prefix}))
    assert foreign['count'] == 1 and foreign['snippets'][0]['id'] == outsider
    assert api('dev-thiago', 'GET', '/linting-rules/printscript/1.1')['revision'] == owner_rules['revision']
    record['phases'] = ['healthy']
else:
    record = json.loads(args.evidence.read_text())
    bad = record['ids']['not-compliant'][0]
    if args.phase == 'processor-down':
        query = urllib.parse.urlencode({'name': record['prefix'], 'lint': 'compliant'})
        failed_list = api('dev-ana', 'GET', '/snippets?' + query, expected=502)
        assert 'snippets' not in failed_list
        api('dev-ana', 'GET', '/snippets/' + bad, expected=502)
    else:
        assert listing(record)['count'] == record['expectedCount']
        api('dev-ana', 'GET', '/snippets/' + bad, expected=503 if args.phase == 'storage-down' else 200)
    record['phases'].append(args.phase)
args.evidence.write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps({'phase': args.phase, 'result': 'passed', 'evidence': str(args.evidence)}))
