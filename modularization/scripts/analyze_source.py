#!/usr/bin/env python3
"""Conservative source analyzer. It reports declarations and line ranges; it does not rewrite Kotlin."""
import argparse, json, re, hashlib
from pathlib import Path

PATTERNS = [
 ('class', r'^(?:\s*@[^\n]+\n\s*)*(?:\s*(?:public|private|internal|protected|open|abstract|final|sealed|data|enum|annotation|inner|value)\s+)*class\s+([A-Za-z_][\w]*)'),
 ('interface', r'^(?:\s*@[^\n]+\n\s*)*(?:\s*(?:public|private|internal|protected|sealed)\s+)*interface\s+([A-Za-z_][\w]*)'),
 ('object', r'^(?:\s*@[^\n]+\n\s*)*(?:\s*(?:public|private|internal|protected)\s+)*object\s+([A-Za-z_][\w]*)'),
 ('enum', r'^(?:\s*@[^\n]+\n\s*)*(?:\s*(?:public|private|internal|protected)\s+)*enum\s+class\s+([A-Za-z_][\w]*)'),
 ('function', r'^(?:\s*@[^\n]+\n\s*)*(?:\s*(?:public|private|internal|protected|inline|infix|operator|suspend|tailrec|external|override)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][\w<>?.]*\.)?([A-Za-z_][\w]*)\s*\('),
]

def main():
 p=argparse.ArgumentParser(); p.add_argument('--source',default='source/MainActivity.kt'); p.add_argument('--out',default='modularization/reports'); a=p.parse_args()
 src=Path(a.source).read_text(encoding='utf-8'); lines=src.splitlines(); out=Path(a.out); out.mkdir(parents=True,exist_ok=True)
 declarations=[]
 for i,line in enumerate(lines,1):
  for kind,pat in PATTERNS:
   m=re.match(pat,line)
   if m:
    name=m.group(1); declarations.append({'kind':kind,'name':name,'line':i,'composable':'@Composable' in '\n'.join(lines[max(0,i-4):i]),'room_annotation':any(x in '\n'.join(lines[max(0,i-4):i]) for x in ('@Entity','@Dao','@Database'))})
    break
 imports=[x.strip() for x in lines if x.strip().startswith('import ')]
 report={'source':a.source,'lines':len(lines),'bytes':len(src.encode()),'sha256':hashlib.sha256(src.encode()).hexdigest(),'package':next((x.strip().split()[1] for x in lines if x.strip().startswith('package ')),None),'imports':imports,'declarations':declarations,'counts':{k:sum(d['kind']==k for d in declarations) for k,_ in PATTERNS},'note':'Regex scan is a preliminary inventory, not a Kotlin parser; do not use it to split source automatically.'}
 (out/'source-analysis.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
 with (out/'declarations.csv').open('w',encoding='utf-8') as f:
  f.write('kind,name,line,composable,room_annotation\n')
  for d in declarations: f.write(f"{d['kind']},{d['name']},{d['line']},{d['composable']},{d['room_annotation']}\n")
 md=['# Source Analysis Report','',f"- Source: `{a.source}`",f"- Lines: {len(lines)}",f"- Bytes: {len(src.encode())}",f"- SHA-256: `{report['sha256']}`",f"- Package: `{report['package']}`",'','## Preliminary declaration counts','']
 md += [f"- {k}: {v}" for k,v in report['counts'].items()]; md += ['', '## Safety note',report['note']]
 (out/'analysis-report.md').write_text('\n'.join(md),encoding='utf-8')
 print(json.dumps({'lines':len(lines),'bytes':len(src.encode()),'declarations':len(declarations),'counts':report['counts'],'sha256':report['sha256']},indent=2))
if __name__=='__main__': main()
