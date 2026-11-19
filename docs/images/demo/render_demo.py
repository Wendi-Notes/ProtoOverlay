import math, os, sys
from PIL import Image, ImageDraw, ImageSequence
# README 用の動く絵（docs/images/demo/*.gif）を、アプリの部品の絵から作り直すスクリプト。
# 使い方：python docs/images/demo/render_demo.py （Pillow が必要）。ProtoView.kt の動きの数値に合わせてある
HERE=os.path.dirname(os.path.abspath(__file__))
R=os.path.join(HERE,"..","..","..","app","src","main","res","drawable-nodpi")+os.sep
def L(n): return Image.open(R+n).convert("RGBA")
P={n:L(f"proto_{n}.png") for n in ["body","eye","eye_blink","eye_wide","eye_sleep","eye_happy","mouth","mouth_open","paws","charge","rec"]}
def anim(n):
    im=Image.open(R+n); fr=[];d=[]
    for f in ImageSequence.Iterator(im): fr.append(f.convert("RGBA")); d.append(f.info.get("duration",80) or 80)
    return fr,d
HAT=anim("hatena_think.webp"); LIS=anim("proto_listen_anim.webp"); ZZZ=anim("proto_zzz_anim.webp")
def at(a,t):
    fr,d=a; tot=sum(d); t%=tot
    for f,x in zip(fr,d):
        if t<x: return f
        t-=x
    return fr[-1]
IMG_W,IMG_H,CUT_Y,PX,PY,TOP=480,309,221,264,222,70
HCX,HCY,HS,HT=418,22,150,-18
def wave(t,p): return (1-math.cos(2*math.pi*(t%p)/p))/2
BG=(24,23,21,255); BAR=(38,36,33,255); STROKE=(70,66,60,255)
W,H=IMG_W, TOP+CUT_Y+46
def frame(t, face="IDLE", motion="SWAY", look=0.0, paws=True, hat=False, listen=False, zzz=False, badge=None, mouth_open=False, doze_k=0.0, hop=None):
    cv=Image.new("RGBA",(W,H),BG)
    # バー（パネル）
    d=ImageDraw.Draw(cv)
    y0=TOP+CUT_Y
    d.rounded_rectangle((-20,y0,W+20,H+30),radius=22,fill=BAR,outline=STROKE,width=3)
    d.rounded_rectangle((150,y0+12,W-90,y0+40),radius=14,fill=(52,49,45,255))
    d.ellipse((W-78,y0+10,W-34,y0+42),fill=(127,216,236,255))
    dy=rot=0.0
    if motion=="SWAY": dy=9; rot=-1.6+3.2*wave(t,3400)
    elif motion=="BOB": dy=7+18*wave(t,450)
    elif motion=="TALK_UP": dy=0; rot=-10+0.8*wave(t,900)
    elif motion=="BREATH": dy=9+7*wave(t,4500)
    elif motion=="DOZE":
        k=doze_k; e=k*k*(3-2*k); rot=7*e+0.8*wave(t,4500); dy=9+20*e+6*wave(t,4500)
    rot-=16*look; dy+=26*look
    if hop is not None and 0<=hop<=400: dy-=math.sin(math.pi*hop/400)*30
    layer=Image.new("RGBA",(IMG_W,IMG_H),(0,0,0,0))
    layer.alpha_composite(P["body"])
    eyes={"IDLE":"eye","BLINK":"eye_blink","WIDE":"eye_wide","SLEEP":"eye_sleep","HAPPY":"eye_happy","TALK":"eye_wide"}
    if face in eyes: layer.alpha_composite(P[eyes[face]])
    if face in ("IDLE","BLINK","WIDE","HAPPY"): layer.alpha_composite(P["mouth"])
    if face=="TALK": layer.alpha_composite(P["mouth_open" if mouth_open else "mouth"])
    if face=="REC": layer.alpha_composite(P["rec"])
    if badge=="CHARGE": layer.alpha_composite(P["charge"])
    # 回転（ピボット中心）→ 平行移動 → 切れ目でクリップ
    big=Image.new("RGBA",(IMG_W,IMG_H+80),(0,0,0,0)); big.alpha_composite(layer,(0,40))
    big=big.rotate(-rot, resample=Image.BICUBIC, center=(PX,PY+40))  # Android の rotate(+) は時計回り
    # 平行移動して、体の切れ目（CUT_Y）より下は描かない
    body=Image.new("RGBA",(IMG_W,CUT_Y),(0,0,0,0))
    off=int(round(dy))-40
    body.alpha_composite(big.crop((0,max(0,-off),IMG_W,min(big.height,CUT_Y-off))),(0,max(0,off)))
    cv.alpha_composite(body,(0,TOP))
    if paws: cv.alpha_composite(P["paws"].crop((0,0,IMG_W,min(IMG_H,H-TOP))),(0,TOP))
    def put(img,cx,cy,size,tilt=0):
        im=img.resize((size,size),Image.LANCZOS)
        if tilt: im=im.rotate(-tilt,resample=Image.BICUBIC,expand=False)
        cv.alpha_composite(im,(int(cx-size/2),int(cy-size/2)))
    if listen and not hat: put(at(LIS,t),HCX,HCY+TOP+10,120)
    if zzz and not hat and not listen: put(at(ZZZ,t),HCX,HCY+TOP+8,140)
    if hat: put(at(HAT,t),HCX,HCY+TOP-5*wave(t,1200),HS,HT)
    return cv
def save(name, fn, dur_ms, step=60, out_w=240):
    frames=[]
    for t in range(0,dur_ms,step):
        f=fn(t).convert("RGB"); f=f.resize((out_w,int(f.height*out_w/f.width)),Image.LANCZOS); frames.append(f)
    pal=[f.quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f in frames]
    pal[0].save(name, save_all=True, append_images=pal[1:], duration=step, loop=0, optimize=True, disposal=1)
    print(name, len(frames), os.path.getsize(name)//1024, "KB")
OUT=HERE
# 1 待機：ゆらゆら＋まばたき
save(OUT+"/idle.gif", lambda t: frame(t, "BLINK" if (t%2400)<140 or 600<(t%2400)<700 and t>2000 else "IDLE","SWAY"), 3400)
# 2 おしゃべり：上を向いて口ぱくぱく
save(OUT+"/talk.gif", lambda t: frame(t,"TALK","TALK_UP",mouth_open=(t//140)%2==0), 1800)
# 3 考え中：？が出る
save(OUT+"/think.gif", lambda t: frame(t,"BLINK" if (t%1800)<900 else "IDLE","SWAY",hat=True), 2400)
# 4 聞き取り中：ハッ
save(OUT+"/listen.gif", lambda t: frame(t,"WIDE","BREATH",listen=True), 2400)
# 5 うれしい：ぴょこぴょこ
save(OUT+"/happy.gif", lambda t: frame(t,"HAPPY","BOB"), 1800)
# 6 居眠り：こっくり→Zzz
save(OUT+"/sleep.gif", lambda t: frame(t,"SLEEP","DOZE",zzz=True,doze_k=min(1,t/2500)), 4500)
# 7 カメラ中：●REC でわくわく
save(OUT+"/rec.gif", lambda t: frame(t,"REC","BOB"), 1800)
# 8 充電：おでこに電池
save(OUT+"/charge.gif", lambda t: frame(t,"HAPPY" if t<900 else "IDLE","SWAY",badge="CHARGE",hop=t), 3000)
