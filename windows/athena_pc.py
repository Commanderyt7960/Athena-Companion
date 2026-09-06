import json
import os
import secrets
import socket
import subprocess
import threading
import time
import webbrowser
import sys
from pathlib import Path
import tkinter as tk

APP_DIR = Path.home() / ".athena"
APP_DIR.mkdir(exist_ok=True)
TOKEN_FILE = APP_DIR / "pairing_token"
MEMORY_FILE = APP_DIR / "memory.json"
PORT = 49321
MODEL_NAME = "gemma-3-1b-it-Q4_K_M.gguf"


def resource_path(*parts):
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base.joinpath(*parts)

TOKEN = TOKEN_FILE.read_text(encoding="utf-8").strip() if TOKEN_FILE.exists() else secrets.token_urlsafe(24)
TOKEN_FILE.write_text(TOKEN, encoding="utf-8")
try:
    MEMORY = json.loads(MEMORY_FILE.read_text(encoding="utf-8")) if MEMORY_FILE.exists() else []
except Exception:
    MEMORY = []


def save_memory():
    MEMORY_FILE.write_text(json.dumps(MEMORY[-500:], ensure_ascii=False, indent=2), encoding="utf-8")


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def open_target(target):
    t = target.strip(); low = t.lower()
    if low.startswith(("http://", "https://")):
        webbrowser.open(t); return "Opening the website."
    urls = {"youtube":"https://www.youtube.com","google":"https://www.google.com","discord":"https://discord.com/app","spotify":"https://open.spotify.com","gmail":"https://mail.google.com"}
    if low in urls:
        webbrowser.open(urls[low]); return f"Opening {low}."
    commands = {"notepad":["notepad.exe"],"calculator":["calc.exe"],"calc":["calc.exe"],"paint":["mspaint.exe"],"explorer":["explorer.exe"],"file explorer":["explorer.exe"],"settings":["ms-settings:"],"task manager":["taskmgr.exe"],"cmd":["cmd.exe"],"powershell":["powershell.exe"]}
    if low == "chrome":
        for p in [os.path.expandvars(r"%ProgramFiles%\Google\Chrome\Application\chrome.exe"),os.path.expandvars(r"%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"),os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe")]:
            if os.path.exists(p): subprocess.Popen([p]); return "Opening Chrome."
        webbrowser.open("https://www.google.com"); return "Chrome was not found, so I opened Google."
    if low == "edge":
        for p in [os.path.expandvars(r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),os.path.expandvars(r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe")]:
            if os.path.exists(p): subprocess.Popen([p]); return "Opening Edge."
    if low in commands:
        subprocess.Popen(commands[low]); return f"Opening {target}."
    return "I don't have that PC application enabled yet."


def close_target(target):
    names={"discord":"Discord.exe","chrome":"chrome.exe","edge":"msedge.exe","spotify":"Spotify.exe","notepad":"notepad.exe","calculator":"CalculatorApp.exe","calc":"CalculatorApp.exe","paint":"mspaint.exe"}
    name=names.get(target.strip().lower())
    if not name: return "I don't have permission to close that application."
    subprocess.run(["taskkill","/IM",name,"/F"],capture_output=True,text=True)
    return f"Closed {target}."


def volume_action(action):
    keys={"up":"{175}","down":"{174}","mute":"{173}"}; key=keys.get(action)
    if not key:return "Unknown volume command."
    ps="$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys('"+key+"')"
    subprocess.run(["powershell","-NoProfile","-NonInteractive","-Command",ps],creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    return {"up":"Volume up.","down":"Volume down.","mute":"Volume muted/unmuted."}[action]


def media_action(action):
    mapping={"play":"{179}","pause":"{179}","next":"{176}","previous":"{177}"}; key=mapping.get(action)
    if not key:return "Unknown media command."
    ps="$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys('"+key+"')"
    subprocess.run(["powershell","-NoProfile","-NonInteractive","-Command",ps],creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    return f"Media {action}."


def local_ai(text):
    model=resource_path("models",MODEL_NAME)
    exe=resource_path("llama_runtime","llama-cli.exe")
    if not model.exists() or not exe.exists():
        return "Athena's bundled local AI is missing from this release."
    history="\n".join(f"{x.get('role','user')}: {x.get('text','')}" for x in MEMORY[-12:])
    system=("You are Athena, a private local Windows AI assistant. You run entirely on this computer. "
            "Be concise, natural, useful and honest. You can explain or perform only the deterministic PC commands exposed by Athena. "
            "Never claim an action happened unless the command handler confirmed it.")
    prompt=f"Recent local memory:\n{history}\n\nUser: {text}"
    cmd=[str(exe),"-m",str(model),"--single-turn","--no-display-prompt","--no-show-timings","--simple-io","--temp","0.7","--top-p","0.95","-n","256","--system-prompt",system,"-p",prompt]
    try:
        r=subprocess.run(cmd,capture_output=True,text=True,timeout=120,cwd=str(exe.parent),creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        answer=(r.stdout or "").strip()
        if answer:
            return answer
    except subprocess.TimeoutExpired:
        return "The local AI took too long to respond."
    except Exception:
        pass
    return "Athena's local AI could not generate a response."


def handle(command):
    raw=command.strip(); low=raw.lower()
    if low in ("ping","status"): return "Athena PC is online and ready."
    if low.startswith("open "): return open_target(raw[5:])
    if low.startswith("close "): return close_target(raw[6:])
    if low in ("volume up","turn volume up","increase volume"): return volume_action("up")
    if low in ("volume down","turn volume down","decrease volume"): return volume_action("down")
    if low in ("mute","unmute","toggle mute"): return volume_action("mute")
    if low in ("play","pause","play pause"): return media_action("play")
    if low=="next track": return media_action("next")
    if low in ("previous track","previous song"): return media_action("previous")
    if low.startswith("remember "):
        item=raw[9:].strip()
        if item: MEMORY.append({"time":time.time(),"role":"memory","text":item}); save_memory(); return "I'll remember that locally."
    if low in ("show memory","what do you remember"):
        return "I remember: "+"; ".join(x.get("text","") for x in MEMORY[-10:]) if MEMORY else "I don't have any saved memories yet."
    answer=local_ai(raw)
    MEMORY.append({"time":time.time(),"role":"user","text":raw}); MEMORY.append({"time":time.time(),"role":"athena","text":answer}); save_memory()
    return answer


class AthenaPC:
    def __init__(self):
        self.root=tk.Tk(); self.root.title("Athena"); self.root.geometry("650x560"); self.root.configure(bg="#080A10"); self.root.protocol("WM_DELETE_WINDOW",self.root.destroy)
        self.build_ui(); self.start_server()
    def label(self,text,size=12,color="#EDEBFF"):
        return tk.Label(self.root,text=text,bg="#080A10",fg=color,font=("Segoe UI",size))
    def build_ui(self):
        self.label("ATHENA",28).pack(pady=(24,0)); self.label("Private local AI • Windows control • phone pairing",12,"#AAA6B8").pack(pady=(0,18))
        self.status=self.label("Starting…",12,"#9FE7C4"); self.status.pack()
        self.label("PC ADDRESS",10,"#777487").pack(pady=(22,3)); self.address=self.label(f"{local_ip()}:{PORT}",15); self.address.pack()
        self.label("PAIRING TOKEN",10,"#777487").pack(pady=(20,3)); token=tk.Entry(self.root,bg="#11141D",fg="#EDEBFF",insertbackground="white",relief="flat",justify="center",font=("Consolas",12)); token.insert(0,TOKEN); token.configure(state="readonly"); token.pack(fill="x",padx=70,ipady=8)
        self.label("LOCAL AI",10,"#777487").pack(pady=(18,3)); self.label("Gemma 3 1B IT • bundled • no API key • no Ollama • offline",11,"#9FE7C4").pack()
        self.log=tk.Text(self.root,height=12,bg="#0E1118",fg="#DAD7E5",insertbackground="white",relief="flat"); self.log.pack(fill="both",expand=True,padx=28,pady=22)
        self.write("Athena is ready. The Windows AI is bundled locally in this release."); self.write("Keep Athena running to accept paired phone commands.")
    def write(self,text): self.log.insert("end",text+"\n"); self.log.see("end")
    def start_server(self): threading.Thread(target=self.server,daemon=True).start(); self.status.configure(text="ONLINE • local AI ready • waiting for paired devices")
    def server(self):
        s=socket.socket(socket.AF_INET,socket.SOCK_STREAM); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(("0.0.0.0",PORT)); s.listen(20)
        while True:
            conn,addr=s.accept(); threading.Thread(target=self.client,args=(conn,addr),daemon=True).start()
    def client(self,conn,addr):
        try:
            conn.settimeout(8); data=b""
            while not data.endswith(b"\n") and len(data)<65536:
                chunk=conn.recv(4096)
                if not chunk: break
                data+=chunk
            req=json.loads(data.decode("utf-8")); ok=secrets.compare_digest(str(req.get("token","")),TOKEN)
            if ok:
                command=str(req.get("command","")).strip(); reply=handle(command); self.root.after(0,lambda:self.write(f"[{addr[0]}] {command} → {reply}"))
            else: reply="Pairing rejected."
            conn.sendall((json.dumps({"ok":ok,"reply":reply})+"\n").encode("utf-8"))
        except Exception:
            try: conn.sendall((json.dumps({"ok":False,"reply":"Athena PC could not process that request."})+"\n").encode())
            except Exception: pass
        finally:
            try: conn.close()
            except Exception: pass
    def run(self): self.root.mainloop()

if __name__=="__main__": AthenaPC().run()
