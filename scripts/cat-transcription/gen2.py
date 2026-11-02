# Regenerates the bundled CAT schema transcription (cat-im-schema-subset-4.1.0r14.json).
# Input: cat-raw.txt in this directory = `pdftotext -raw` of the CAT IM Technical
# Specifications v4.1.0 r14 (not committed). Run from this directory: python gen2.py
# Output: schema.json (copy over the bundled resource) and meta.json (per-type rules,
# read by genjava.py). See docs/learn/04-technology.md section 17n.
import json,re,sys
exec(open('parse.py').read().split('for n in sys.argv')[0])
exec(open('parse2.py').read())
g=open('gen_schema.py').read()
exec(g[g.index('FULL='):g.index('events=[]')])
OLD=[16,17,18,39,48,52,62,63,64,70,79,83,85,90,92,100,102,126,139]
SIMPLE=[65,66,67,68,69,71,72,73,74,75,76,77,78,80,81,82,84,86,87,88,89,94,96,97,98,99]
MULTI=[104,106,107,109,111,113,114,116,117,119,121,122,124,127,128,130,132,134,136,137,141]
COMMON={"actionType","firmROEID","type","symbol","eventTimestamp"}
def block(n,field):
    start=[i for i,l in enumerate(L) if l.startswith(f"Table {n}:")][-1]
    end=[i for i,l in enumerate(L[start+1:],start+1) if re.match(r"^Table \d+:",l) or l.startswith("Linkage Keys")][0]
    txt=L[start:end]; out=[];on=False;seq=None
    for l in txt:
        m=re.match(r"^(\d+) (\w+)( |$)",l)
        if m: on=(m.group(2)==field); seq=m.group(1) if on else seq
        if on: out.append(l)
    return ' '.join(out),seq
events=[];meta=[]
for n in OLD+SIMPLE+MULTI:
    start=[i for i,l in enumerate(L) if l.startswith(f"Table {n}:")][-1]
    name=re.search(r"\(([A-Z]{4,5})\)",L[start]+' '+L[start+1]+' '+L[start+2]).group(1)
    title=L[start].split(':',1)[1].strip()
    fields=[];req=[];bools=[]
    t=table2(n) if n not in OLD else table(n)
    for s,nm,dt,k in t:
        if nm in("retiredFieldPosition","reservedForFutureUse"): continue
        if dt.startswith("Message"): dt="Message Type"
        dt=FULL.get(dt,dt)
        mm=re.match(r"Text\((\d+)\)",dt)
        if mm: dt=f"Text ({mm.group(1)})"
        if dt=="Numeric":
            b,_=block(n,nm); mm=re.search(r"Numeric\s*\((\d+),(\d+)\)",b); dt=f"Numeric ({mm.group(1)},{mm.group(2)})"
        if k=="?": k=KEYFIX[nm]
        if dt=="Alphanumeric" and nm=="seqNum": dt="Alphanumeric (40)"
        if dt=="Industry Member ID" and re.search(r"Industry\s+Member ID\s*/\s*Exchange ID",block(n,nm)[0]):
            dt=["Industry Member ID","Exchange ID"]  # cell wraps over lines: "Industry Member ID / Exchange ID"
        jt="STRING" if isinstance(dt,list) or dt.startswith("Text") or dt.startswith("Alphanumeric") else ("NUMBER" if isinstance(dt,str) and dt.startswith("Numeric") else JT[dt])
        fields.append({"name":nm,"dataType":dt,"JSONDataType":jt,"required":REQ[k],"position":str(s)})
        if k=='R' and dt!='Boolean' and nm not in COMMON: req.append(nm)
        if k=='R' and dt=='Boolean': bools.append(nm)
    events.append({"eventName":name,"source":f"Table {n}","fields":fields})
    if n in OLD: continue
    pb,ps=block(n,'price')
    names=[f['name'] for f in fields]
    m={"n":n,"name":name,"title":title,"req":req,"bools":bools,"multi":n in MULTI,
       "nonneg":ps if 'greater than or equal to zero' in pb else None,
       "lmt":ps if re.search(r"Required when orderType is .LMT",pb) else None,
       "mkt":ps if re.search(r"blank when orderType is .MKT",pb) else None}
    for fn in ("initiator","senderType","priceType","side","optionID","orderType"):
        b,s=block(n,fn); m[fn]=s if b else None
        if fn=="priceType" and b: m["ptLmt"]=bool(re.search(r"LMT",b))
    meta.append(m)
json.dump(meta,open('meta.json','w'),indent=1)
choices=json.loads('['+g[g.index('choices=[')+9:g.index(']\ndoc=')]+']'.replace(',\n]',']')) if False else None
gs=open('gen_schema.py').read()
exec(gs[gs.index('EQ=['):gs.index('doc=')])
mm={m['name']:m for m in meta}
side_new=[m['name'] for m in meta if m['side']]
for c in choices:
    if c['name']=='side' and c.get('eventNames')==["MONO","MOOR","MOOA","MOOM"]: c['eventNames']+=side_new
    if c['name']=='senderType': c['eventNames']+=[m['name'] for m in meta if m['senderType'] and m['name'] not in('MOQR','MLQR')]
    if c['name']=='fulfillmentLinkType' and c['eventNames']==["MOOF"]: c['eventNames'].append("MOFA")
choices.append({"name":"senderType","eventNames":["MOQR","MLQR"],"values":["F","N","S"],"source":"Appendix G, senderType (quote events)"})
TABLES=OLD+SIMPLE+MULTI
doc={"_provenance":"Library transcription, NOT the official CAT Industry Member Schema file. Field names, data types, Include Key and CSV positions of CAT Reporting Technical Specifications for Industry Members v4.1.0 r14 (2026-02-13) Tables "+", ".join(map(str,TABLES))+"; choice values from Appendix G; layout per section 6.1.5.2. Retired and reserved positions left out. Generated with pdftotext -raw and the transcription scripts in scripts/cat-transcription of the library repository (gen2.py). Since 3.2.0: destination and senderIMID read as 'Industry Member ID / Exchange ID' where the table prints both (a JSON array of alternatives), found by comparison with the official 4.2.0 file.",
 "schemaVersion":"4.1.0","eventDefinitions":events,"choices":choices}
open('schema.json','w',newline='\n').write(json.dumps(doc,indent=1)+"\n")
