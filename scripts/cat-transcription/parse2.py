import re
exec(open('parse.py').read().split('def table')[0])
KS=('R','C','O','A')
def table2(n):
    start=[i for i,l in enumerate(L) if l.startswith(f"Table {n}:")][-1]
    end=[i for i,l in enumerate(L[start+1:],start+1) if re.match(r"^Table \d+:",l) or l.startswith("Linkage Keys")][0]
    blocks=[];exp=1
    for l in L[start:end]:
        m=re.match(r"^(\d+) (\w+)( (.*))?$",l)
        if m and int(m.group(1))==exp:
            blocks.append([exp,m.group(2),m.group(4) or '',[l]]);exp+=1;continue
        if blocks: blocks[-1][3].append(l)
    out=[]
    for s,nm,rest,lines in blocks:
        k='?'
        for l in lines:
            t=l.strip()
            if t in KS or (len(t)>2 and t[-2]==' ' and t[-1] in KS):
                k=t[-1];break
        dt=next((t for t in TYPES if rest.startswith(t)),rest[:20])
        if dt=="Text":
            m=re.match(r"Text \((\d+)\)",rest); dt=f"Text({m.group(1)})" if m else dt
        out.append((s,nm,dt,k))
    return out
