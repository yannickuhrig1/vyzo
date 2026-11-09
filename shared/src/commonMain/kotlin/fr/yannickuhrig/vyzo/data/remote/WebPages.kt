package fr.yannickuhrig.vyzo.data.remote

/** Pages servies au téléphone qui scanne le QR code de la TV (aucune ressource externe : tout fonctionne en réseau local). */
object WebPages {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val STYLE = """
:root{--bg:#0B0F17;--card:#121826;--field:#1A2232;--line:#2E384B;--text:#E8ECF3;--dim:#9AA5B8;--amber:#FFB547;--red:#FF5D5D;--ok:#5EC8D8}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,Roboto,"Segoe UI",sans-serif;padding:22px 16px 40px}
main{max-width:520px;margin:0 auto}
h1{font-size:24px;margin:0 0 4px}
h1 span{color:var(--amber)}
p.lead{color:var(--dim);margin:0 0 20px}
.tabs{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:6px}
.tabs button{background:var(--card);color:var(--text);border:2px solid transparent;border-radius:12px;padding:12px 6px;font-weight:600;font-size:15px;cursor:pointer}
.tabs button small{display:block;color:var(--dim);font-weight:400;font-size:12px;margin-top:2px}
.tabs button.on{border-color:var(--amber);color:var(--amber)}
label{display:block;font-size:13px;color:var(--dim);margin:14px 0 6px}
input{width:100%;background:var(--field);border:1px solid var(--line);color:var(--text);border-radius:10px;padding:13px 12px;font-size:16px}
input:focus{outline:none;border-color:var(--amber)}
.hide{display:none}
button.go{width:100%;margin-top:24px;background:var(--amber);color:#241600;border:0;border-radius:24px;padding:15px;font-size:17px;font-weight:700;cursor:pointer}
button.go:disabled{opacity:.5}
#msg{margin-top:18px;padding:14px;border-radius:12px;background:var(--card);display:none}
#msg.err{color:var(--red)}
#msg.ok{color:var(--ok)}
details{margin-top:14px;color:var(--dim)}
footer{margin-top:28px;color:var(--dim);font-size:13px}
"""

    fun form(token: String, deviceName: String): String = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><title>Vyzo · configuration</title><style>$STYLE</style></head>
<body><main>
<h1><span>Vyzo</span> · {{DEVICE}}</h1>
<p class="lead">Saisis ta playlist ici : elle part directement sur ton téléviseur, sans rien taper à la télécommande.</p>
<div class="tabs">
 <button type="button" data-t="xtream" class="on">Xtream<small>serveur + identifiants</small></button>
 <button type="button" data-t="m3u">M3U<small>lien de playlist</small></button>
 <button type="button" data-t="stalker">Stalker<small>portail + MAC</small></button>
</div>
<form id="f" autocomplete="off">
 <label for="name">Nom (facultatif)</label><input id="name" placeholder="Mon abonnement">
 <div data-for="xtream">
  <label for="xurl">Serveur</label><input id="xurl" inputmode="url" placeholder="http://exemple.com:8080" autocapitalize="off" spellcheck="false">
  <label for="user">Identifiant</label><input id="user" autocapitalize="off" spellcheck="false">
  <label for="pass">Mot de passe</label><input id="pass" type="password" autocapitalize="off">
 </div>
 <div data-for="m3u" class="hide">
  <label for="murl">Lien de la playlist M3U</label><input id="murl" inputmode="url" placeholder="http://…/playlist.m3u" autocapitalize="off" spellcheck="false">
  <label for="epg">Guide TV XMLTV (facultatif)</label><input id="epg" inputmode="url" placeholder="http://…/guide.xml.gz" autocapitalize="off" spellcheck="false">
 </div>
 <div data-for="stalker" class="hide">
  <label for="surl">Adresse du portail</label><input id="surl" inputmode="url" placeholder="http://portail.tv:8080/c/" autocapitalize="off" spellcheck="false">
  <label for="mac">Adresse MAC</label><input id="mac" value="00:1A:79:" autocapitalize="characters" spellcheck="false">
 </div>
 <details><summary>Options avancées</summary><label for="ua">User-Agent</label><input id="ua" autocapitalize="off" spellcheck="false"></details>
 <button class="go" id="go" type="submit">Envoyer à la TV</button>
</form>
<details><summary>Restaurer un fichier de sauvegarde</summary>
 <label for="file">Fichier vyzo-sauvegarde.json de ce téléphone</label><input id="file" type="file" accept=".json,application/json,text/plain">
 <button class="go" id="send" type="button">Envoyer le fichier à la TV</button>
</details>
<div id="msg"></div>
<footer>Ce lien ne fonctionne que sur ton réseau local et tant que l'écran « Configurer depuis le téléphone » reste ouvert sur la TV.</footer>
</main>
<script>
var K="{{TOKEN}}",type="xtream";
var tabs=document.querySelectorAll(".tabs button");
function el(id){return document.getElementById(id)}
function v(id){return el(id).value.trim()}
function show(t,cls){var m=el("msg");m.style.display="block";m.className=cls||"";m.textContent=t}
for(var i=0;i<tabs.length;i++){tabs[i].onclick=function(){
 type=this.getAttribute("data-t");
 for(var j=0;j<tabs.length;j++){tabs[j].className=tabs[j]===this?"on":""}
 var blocks=document.querySelectorAll("[data-for]");
 for(var j=0;j<blocks.length;j++){blocks[j].className=blocks[j].getAttribute("data-for")===type?"":"hide"}
}}
function poll(){fetch("/api/status?k="+K).then(function(r){return r.json()}).then(function(s){
 if(s.state==="done"){show("✓ "+s.message,"ok");el("go").disabled=false;return}
 if(s.state==="error"||s.state==="rejected"){show(s.message,"err");el("go").disabled=false;return}
 show("⏳ "+s.message);setTimeout(poll,1500)
}).catch(function(){show("La TV ne répond plus : l'écran de configuration a peut-être été fermé.","err");el("go").disabled=false})}
el("f").onsubmit=function(e){e.preventDefault();
 var p={type:type,name:v("name"),userAgent:v("ua")};
 if(type==="xtream"){p.url=v("xurl");p.username=v("user");p.password=el("pass").value}
 if(type==="m3u"){p.url=v("murl");p.epgUrl=v("epg")}
 if(type==="stalker"){p.url=v("surl");p.mac=v("mac").toUpperCase()}
 el("go").disabled=true;show("Envoi…");
 fetch("/api/playlist?k="+K,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(p)})
  .then(function(r){return r.json()})
  .then(function(r){if(r.ok){show("Reçu par la TV, chargement en cours…");setTimeout(poll,1200)}else{show(r.error||"Erreur","err");el("go").disabled=false}})
  .catch(function(){show("Impossible de joindre la TV. Le téléphone doit être connecté au même réseau Wi-Fi.","err");el("go").disabled=false})
};
el("send").onclick=function(){
 var file=el("file").files[0];
 if(!file){show("Choisis d'abord le fichier de sauvegarde.","err");return}
 show("Envoi…");
 file.text().then(function(text){return fetch("/api/config?k="+K,{method:"POST",headers:{"Content-Type":"application/json"},body:text})})
  .then(function(r){return r.json()})
  .then(function(r){if(r.ok){setTimeout(poll,800)}else{show(r.error||"Erreur","err")}})
  .catch(function(){show("Impossible de joindre la TV. Le téléphone doit être connecté au même réseau Wi-Fi.","err")})
};
</script>
</body></html>""".replace("{{TOKEN}}", token).replace("{{DEVICE}}", esc(deviceName))

    /** Connexion de la TV au compte Vyzo, saisie sur le téléphone. */
    fun accountForm(token: String, deviceName: String): String = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><title>Vyzo · compte</title><style>$STYLE</style></head>
<body><main>
<h1><span>Vyzo</span> · {{DEVICE}}</h1>
<p class="lead">Connecte ta TV à ton compte Vyzo : elle retrouvera les profils, les favoris et la progression de tes autres appareils.</p>
<form id="f" autocomplete="on">
 <label for="email">Adresse e-mail</label><input id="email" type="email" inputmode="email" autocomplete="username" autocapitalize="off" spellcheck="false">
 <label for="pass">Mot de passe</label><input id="pass" type="password" autocomplete="current-password">
 <button class="go" id="go" type="submit">Connecter la TV</button>
</form>
<div id="msg"></div>
<footer>Le mot de passe passe uniquement par ton réseau local, de ce téléphone à la TV, qui se connecte ensuite elle-même au compte.</footer>
</main>
<script>
var K="{{TOKEN}}";
function el(id){return document.getElementById(id)}
function show(t,cls){var m=el("msg");m.style.display="block";m.className=cls||"";m.textContent=t}
function poll(){fetch("/api/status?k="+K).then(function(r){return r.json()}).then(function(s){
 if(s.state==="done"){show("✓ "+s.message,"ok");el("go").disabled=false;return}
 if(s.state==="error"){show(s.message,"err");el("go").disabled=false;return}
 show("⏳ "+s.message);setTimeout(poll,1200)
}).catch(function(){show("La TV ne répond plus : l'écran de connexion a peut-être été fermé.","err");el("go").disabled=false})}
el("f").onsubmit=function(e){e.preventDefault();
 el("go").disabled=true;show("Envoi…");
 fetch("/api/account?k="+K,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:el("email").value.trim(),password:el("pass").value})})
  .then(function(r){return r.json()})
  .then(function(r){if(r.ok){setTimeout(poll,800)}else{show(r.error||"Erreur","err");el("go").disabled=false}})
  .catch(function(){show("Impossible de joindre la TV. Le téléphone doit être connecté au même réseau Wi-Fi.","err");el("go").disabled=false})
};
</script>
</body></html>""".replace("{{TOKEN}}", token).replace("{{DEVICE}}", esc(deviceName))

    fun expired(): String = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vyzo</title><style>$STYLE</style></head><body><main>
<h1><span>Vyzo</span></h1>
<p class="lead">Ce code n'est plus valable. Ouvre à nouveau « Configurer depuis le téléphone » sur la TV et scanne le nouveau QR code.</p>
</main></body></html>"""
}
