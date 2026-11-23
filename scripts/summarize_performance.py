"""Summarize the actual bounded gsql EXPLAIN log; never fabricates timings."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--load-log', type=Path, required=True)
    p.add_argument('--measure-log', type=Path, required=True)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        raise ValueError('Will not overwrite a performance result')
    load = a.load_log.read_text(encoding='utf-8')
    text = a.measure_log.read_text(encoding='utf-8')
    manifest = json.loads(a.manifest.read_text(encoding='utf-8'))
    if '6.0.2' not in text or not re.search(r'openGauss', text, re.I):
        raise ValueError('Actual openGauss6.0.2 version output required')
    if 'database|course_app_v2_perf_test' not in text or not re.search(r'^\s*COMMIT\s*$', text, re.M):
        raise ValueError('Actual dedicated database marker and committed index restoration required')
    if re.search(r'\b(?:ERROR|FATAL|PANIC):', load + '\n' + text):
        raise ValueError('Database error found; keep raw logs and investigate')
    for marker in ['rows|students|10000', 'rows|sections|600', 'rows|enrollments|100000', 'count_mismatches|0']:
        if marker not in load:
            raise ValueError('Missing real data/count marker: ' + marker)
    scenarios = ('without_section_index', 'with_section_index')
    if manifest['measurement'].get('scenarios') != list(scenarios):
        raise ValueError('Real DROP/CREATE before/after data manifest required')
    if not re.search(r'^\s*DROP INDEX\s*$', text, re.M) or not re.search(r'^\s*CREATE INDEX\s*$', text, re.M):
        raise ValueError('Actual successful DROP INDEX and CREATE INDEX command tags required')
    if 'INDEX_DROP_COMPLETED' not in text or 'INDEX_CREATE_COMPLETED' not in text or 'planner_defaults|on|on' not in text:
        raise ValueError('Real DDL completion markers and natural planner defaults required')
    if re.search(r'^\s*SET\s+enable_(?:indexscan|bitmapscan)', text, re.M | re.I):
        raise ValueError('Do not force planner settings in a real index before/after comparison')
    for marker in ('index_catalog|without_section_index|0', 'index_catalog|with_section_index|1', 'index_catalog|restored|1', 'MEASUREMENT_END'):
        if marker not in text:
            raise ValueError('Missing actual index catalog/restoration marker: ' + marker)
    result_rows = {}
    for scenario in scenarios:
        matches = re.findall(r'result_rows\|' + scenario + r'\|([0-9]+)', text)
        if len(matches) != 1:
            raise ValueError('One actual result count required for ' + scenario)
        result_rows[scenario] = int(matches[0])
    if set(result_rows.values()) != {manifest['measurement']['expected_result_rows']}:
        raise ValueError('Before/after query result counts differ from the deterministic dataset')
    definitions = re.findall(r'index_definition\|restored\|([^\r\n]+)', text)
    if len(definitions) != 1 or not re.search(r'CREATE INDEX cs2_enrollment_section_idx ON (?:public\.)?cs2_enrollment\b', definitions[0].replace('"', ''), re.I) or not re.search(r'\(\s*section_id\s*,\s*state\s*\)', definitions[0].replace('"', ''), re.I):
        raise ValueError('Restored index definition must match the original section/state index')
    samples = {scenario: [] for scenario in scenarios}
    marker = None
    for line in text.splitlines():
        m = re.search(r'(warmup|measurement)\|(without_section_index|with_section_index)(?:\|([1-7]))?', line)
        if m:
            marker = m.groups()
        timing = re.search(r'(?:Total runtime|Execution Time):\s*([0-9]+(?:\.[0-9]+)?)\s*ms', line, re.I)
        if timing and marker:
            kind, scenario, number = marker
            if kind == 'measurement':
                samples[scenario].append({'iteration': int(number), 'elapsed_ms': float(timing.group(1))})
            marker = None
    summary = {}
    for scenario, rows in samples.items():
        if [r['iteration'] for r in rows] != list(range(1, 8)):
            raise ValueError('Seven ordered real measured plans required for ' + scenario)
        values = [r['elapsed_ms'] for r in rows]
        summary[scenario] = {'samples': rows, 'min_ms': min(values), 'median_ms': statistics.median(values),
                             'max_ms': max(values), 'mean_ms': statistics.mean(values)}
    result = {'status': 'PASS_BOUNDED_SYNTHETIC_QUERY_MEASUREMENT', 'database': 'course_app_v2_perf_test',
              'comparison_type': 'REAL_INDEX_DROP_CREATE', 'rows': manifest['rows'], 'summary': summary,
              'query_result_rows': result_rows, 'restored_index_definition': definitions[0],
              'inputs': {str(path): {'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                         for path in (a.load_log, a.measure_log, a.manifest)},
              'limits': 'One synthetic query at concurrency1; warmup1 and measured7 per scenario. Only the dedicated performance DB nonconstraint section/state index is dropped and recreated. Natural planner, equal result counts and restored catalog are checked. No production capacity, QPS or p95 claim.'}
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with a.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'status': result['status'], 'output': str(a.output)}))


if __name__ == '__main__':
    main()
