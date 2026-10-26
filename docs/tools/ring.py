import zlib,struct,sys
def load(p):
    d=open(p,'rb').read(); i=8; idat=b''
    while i<len(d):
        n=struct.unpack('>I',d[i:i+4])[0]; t=d[i+4:i+8]; c=d[i+8:i+8+n]
        if t==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',c[:10])
        if t==b'IDAT': idat+=c
        i+=12+n
    bpp={2:3,6:4}[ct]; raw=zlib.decompress(idat); s=w*bpp; rows=[]; prev=bytearray(s); o=0
    for y in range(h):
        f=raw[o]; o+=1; line=bytearray(raw[o:o+s]); o+=s
        for x in range(s):
            a=line[x-bpp] if x>=bpp else 0; b=prev[x]; cc=prev[x-bpp] if x>=bpp else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+b)&255
            elif f==3: line[x]=(line[x]+(a+b)//2)&255
            elif f==4:
                pp=a+b-cc; pa=abs(pp-a); pb=abs(pp-b); pc=abs(pp-cc)
                line[x]=(line[x]+(a if pa<=pb and pa<=pc else b if pb<=pc else cc))&255
        rows.append(line); prev=line
    return w,h,bpp,rows
def yellow(px): return px[0]>200 and 150<px[1]<225 and px[2]<100
w,h,bpp,rows=load(sys.argv[1]); y=int(sys.argv[2]); x=int(sys.argv[3])
r=rows[y]; xs=[i for i in range(w) if yellow(r[i*bpp:i*bpp+3])]
print('row',y,'yellow x runs:', xs[:6], '...', xs[-6:])
ys=[j for j in range(h) if yellow(rows[j][x*bpp:x*bpp+3])]
print('col',x,'yellow y:', ys[:6],'...',ys[-6:])
