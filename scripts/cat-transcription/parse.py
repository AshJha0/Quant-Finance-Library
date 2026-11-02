import re,sys
L=open('cat-raw.txt',encoding='utf-8',errors='replace').read().split('\n')
TYPES=["Choice","Unsigned","Text","Message Type","CAT Reporter","Timestamp","Boolean","Price","Real Quantity","Whole","Name/Value","Symbol","Industry","Exchange","Alphanumeric","Integer","Date","Leg Details","Aggregated","Trade Side","Fulfillment","Multi-","Array","Numeric","CAT Submitter"]
def table(n):
    start=[i for i,l in enumerate(L) if l.startswith(f"Table {n}:")][-1]
    end=[i for i,l in enumerate(L[start+1:],start+1) if re.match(r"^Table \d+:",l) or l.startswith("Linkage Keys")][0]
    fields=[];cur=None;exp=1
    for l in L[start:end]:
        m=re.match(r"^(\d+) (\w+)( (.*))?$",l)
        if m and int(m.group(1))==exp:
            rest=(m.group(4) or '');tk=rest.split(' ')[-1] if rest else '';cur=[exp,m.group(2),rest,tk if tk in ('R','C','O','A') else '?'];fields.append(cur);exp+=1;continue
        if cur and l.strip() in ('R','C','O','A'): cur[3]=l.strip()
    out=[]
    for s,nm,rest,k in fields:
        dt=next((t for t in TYPES if rest.startswith(t)),rest[:20])
        if dt=="Text":
            m=re.match(r"Text \((\d+)\)",rest); dt=f"Text({m.group(1)})" if m else dt
        out.append((s,nm,dt,k))
    return out
for n in sys.argv[1:]:
    t=table(n); print(n, ' '.join(f"{s}:{nm}:{dt}:{k}" for s,nm,dt,k in t))
