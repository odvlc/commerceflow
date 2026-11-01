#!/usr/bin/env python3
# Summarizes scenario evidence. Procedural success does not establish factual accuracy.
"""Summarize retained synthetic local-model evidence; this is not a factual-grounding judge."""
import argparse
import json
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('report', type=Path)
parser.add_argument('--require-complete', action='store_true', help='require all 80 frozen cases in three repeats')
args = parser.parse_args()
report = json.loads(args.report.read_text())
cases = report['cases']
keys = [(case['repeat'], case['id']) for case in cases]
if len(keys) != len(set(keys)):
    raise ValueError('Duplicate evaluation scenario/repeat')
corpus = json.loads(
    (Path(__file__).resolve().parent.parent / 'ai-service/src/test/resources/evaluation-cases.json').read_text())
expected = {(repeat, case['id']) for repeat in range(1, 4) for case in corpus}
# Require every scenario and repeat exactly once before reporting a complete evaluation.
if args.require_complete and set(keys) != expected:
    raise ValueError(f'Incomplete evaluation: {len(expected - set(keys))} missing scenarios')


def fraction(items, key):
    return {'numerator': sum(item.get(key) is True for item in items), 'denominator': len(items)}


summary = {
    'report': str(args.report), 'cases': len(cases),
    'completeThreeRepeats': set(keys) == expected,
    'scenarioPass': fraction(cases, 'passed'),
    'unsafeEffects': sum(case.get('unsafeEffect') is True for case in cases),
    'safetyAssessed': sum('unsafeEffect' in case for case in cases),
    'schemaRejectedCalls': sum(tool.get('result', {}).get('errorCode') == 'AI_TOOL_ARGUMENTS_INVALID'
                               for case in cases for tool in case.get('tools', [])),
    'applicationDenials': sum(case.get('applicationDenied') is True for case in cases),
    'states': dict(Counter(case.get('state', 'unrecorded') for case in cases)),
    'grounding': 'Separate source/factual review required; valid references alone do not establish supported assertions',
    'splits': {},
}
for split in ('development', 'holdout'):
    selected = [case for case in cases if case['split'] == split]
    retrieval = [case for case in selected if case['category'] == 'retrieval']
    answerable = [case for case in selected if case.get('answerableTask') is True]
    summary['splits'][split] = {
        'scenarioPass': fraction(selected, 'passed'),
        'recallAt5': fraction(retrieval, 'retrievalHitAt5'),
        'hybridUsed': fraction(retrieval, 'semanticRetrieval'),
        'expectedSourceCited': fraction(retrieval, 'expectedSourceCited'),
        'answerableTaskCompletion': fraction(answerable, 'taskComplete'),
    }
print(json.dumps(summary, indent=2))
