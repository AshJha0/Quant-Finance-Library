import json,re,sys
sys.argv=['x']
exec(open('parse.py').read().split('for n in sys.argv')[0])
TABLES=[16,17,18,39,48,52,62,63,64,70,79,83,85,90,92,100,102,126,139]
FULL={"Message":"Message Type","Message Type":"Message Type","CAT":"CAT Reporter IMID","CAT Reporter":"CAT Reporter IMID",
 "Real":"Real Quantity","Real Quantity":"Real Quantity","Whole":"Whole Quantity","Industry":"Industry Member ID",
 "Name/Value":"Name/Value Pairs","Aggregated":"Aggregated Orders","Trade Side":"Trade Side Details",
 "Fulfillment":"Fulfillment Side Details","Exchange":"Exchange ID"}
KEYFIX={"type":"R","timeInForce":"R","handlingInstructions":"C","clientDetails":"R","firmDetails":"C","legDetails":"R",
 "quantity":"R","minQty":"C","orderType":"R","side":"R","affiliateFlag":"R"}
REQ={"R":"Required","C":"Conditional","O":"Optional","A":"Conditional"}
JT={"Choice":"STRING","Unsigned":"NUMBER","Timestamp":["STRING","NUMBER"],"Boolean":"BOOLEAN","Price":"NUMBER",
 "Real Quantity":"NUMBER","Whole Quantity":"NUMBER","Integer":"NUMBER","Date":"NUMBER","Name/Value Pairs":"OBJECT",
 "Symbol":"STRING","Message Type":"STRING","CAT Reporter IMID":"STRING","Exchange ID":"STRING","Industry Member ID":"STRING",
 "Aggregated Orders":"ARRAY","Trade Side Details":"ARRAY","Fulfillment Side Details":"ARRAY","Leg Details":"ARRAY","Array":"ARRAY"}
events=[]
for n in TABLES:
    start=[i for i,l in enumerate(L) if l.startswith(f"Table {n}:")][-1]
    name=re.search(r"\(([A-Z]{4,5})\)",L[start+1]).group(1)
    fields=[]
    for s,nm,dt,k in table(n):
        if nm in("retiredFieldPosition","reservedForFutureUse"): continue
        dt=FULL.get(dt,dt)
        m=re.match(r"Text\((\d+)\)",dt)
        if m: dt=f"Text ({m.group(1)})"
        if k=="?": k=KEYFIX[nm]
        if dt=="Alphanumeric" and nm=="seqNum": dt="Alphanumeric (40)"
        jt="STRING" if dt.startswith("Text") or dt.startswith("Alphanumeric") else JT[dt]
        fields.append({"name":nm,"dataType":dt,"JSONDataType":jt,"required":REQ[k],"position":str(s)})
    events.append({"eventName":name,"source":f"Table {n}","fields":fields})
EQ=["MENO","MEOR","MEOT","MEOF","MENQ","MENOS"]
choices=[
 {"name":"actionType","values":["NEW","COR","DEL","RPR"],"source":"Appendix G, actionType"},
 {"name":"side","eventNames":["MENO","MEOR"],"values":["B","SL","SS","SX"],"source":"Appendix G, side (equity)"},
 {"name":"side","eventNames":["MONO","MOOR","MOOA","MOOM"],"values":["B","S"],"source":"Appendix G, side (option)"},
 {"name":"capacity","values":["A","P","R"],"source":"Appendix G, capacity"},
 {"name":"sideDetailsInd","values":["BUY","SELL","NA"],"source":"Appendix G, sideDetailsInd"},
 {"name":"senderType","eventNames":["MOOA"],"values":["E","F","O"],"source":"Appendix G, senderType (order events)"},
 {"name":"initiator","values":["C","F"],"source":"Appendix G, initiator"},
 {"name":"priceType","values":["PU","TC","TS"],"source":"Appendix G, priceType"},
 {"name":"fulfillmentLinkType","eventNames":["MEOF"],"values":["FOR","Y","YE","YP","YS"],"source":"Appendix G, fulfillmentLinkType (equity events)"},
 {"name":"fulfillmentLinkType","eventNames":["MOOF"],"values":["O","OS","OE"],"source":"Appendix G, fulfillmentLinkType (option events)"},
]
doc={"_provenance":"Library transcription, NOT the official CAT Industry Member Schema file. Field names, data types, Include Key and CSV positions of CAT Reporting Technical Specifications for Industry Members v4.1.0 r14 (2026-02-13) Tables "+", ".join(map(str,TABLES))+"; choice values from Appendix G; layout per section 6.1.5.2. Retired and reserved positions left out. Generated with pdftotext -raw and a transcription script kept in the QFL review notes (v3.1b_handback_cat.md).",
 "schemaVersion":"4.1.0","eventDefinitions":events,"choices":choices}
out=sys.stdout if len(sys.argv)<2 else None
print(json.dumps(doc,indent=1))
