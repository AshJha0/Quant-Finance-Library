import json,re
meta=json.load(open('meta.json'))
FAM={}
for f,ns in {"ORDER":"MOOA MLOA","SUPPLEMENT":"MOORS MOMRS MOCRS MOOMS MOOFS MLQS","ROUTE":"MOMR MOCR MLMR MLCR",
 "INTERNAL_ROUTE":"MOIR MOIM MOIC MOIMR MOICR MLIR MLIM MLIC MLIMR MLICR","CHILD_ORDER":"MOCO MOCOM MOCOC MLCO MLCOM MLCOC",
 "MODIFICATION":"MOOMR MOOJ MLOM MLOMR","CANCELLATION":"MOOCR MLOCR","QUOTE":"MORQ MOQR MOQC MOQM MLNQ MLRQ MLQR MLQC MLQM",
 "FULFILLMENT":"MOFA","ALLOCATION":"MOPA MOAA","EFFECTIVE":"MOOE MLOE"}.items():
    for n in ns.split(): FAM[n]=f
def wrap(items,indent,first):
    out=[];line=first
    for i,x in enumerate(items):
        tok=f'"{x}"'+(', ' if i<len(items)-1 else '')
        if len(line)+len(tok.rstrip())>118: out.append(line.rstrip());line=indent
        line+=tok
    out.append(line);return '\n'.join(out)
groups=[("Simple option events of Table 60 (since 3.2.0)",[m for m in meta if not m['multi']]),
        ("Multi-leg option events of Table 61 (since 3.2.0)",[m for m in meta if m['multi']])]
enum=[]
for title,ms in groups:
    enum.append(f"\n    // ---- {title} ----")
    for m in ms:
        nm=m['name'];t=m['title'].replace(' Field Specifications','').replace(' Specifications','')
        b=(" Booleans "+", ".join(m['bools'])+" are 'R' but left out as above.") if m['bools'] else ""
        enum.append(f"""    /**
     * {t} (Table {m['n']}).{b}
     *
     * @since 3.2.0
     */
""" + wrap(m['req'],"            ",f"    {nm}(List.of(")+"))," )
open('enum.txt','w').write('\n'.join(enum))
fam={m['name']:FAM[m['name']] for m in meta}
open('fam.json','w').write(json.dumps(fam))
# rules class rows
rows=[]
for m in meta:
    def s(k): return m[k] if m[k] else "0"
    q=m['name'] in("MOQR","MLQR")
    pt="0"
    if m['priceType']: pt=m['priceType']
    rows.append(f"            row(CatEventType.{m['name']}, {m['n']}, {s('nonneg')}, {s('lmt')}, {s('mkt')}, {s('initiator')}, {s('senderType')}, {'true' if q else 'false'}, {pt}),")
open('rows.txt','w').write('\n'.join(rows))
print(len(meta))
