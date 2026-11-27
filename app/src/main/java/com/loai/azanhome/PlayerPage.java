package com.loai.azanhome;

/** صفحة مشغّل يوتيوب المضمّن، يشترك فيها مشغّل WebView ومشغّل Firefox. لا تعتمد على أندرويد. */
final class PlayerPage {

    private PlayerPage() {
    }

    /**
     * type: p = قائمة تشغيل ، v = فيديو مفرد (يعاد تشغيله تلقائياً عند انتهائه).
     * origin: عنوان الصفحة المضيفة. extra: سكربت إضافي يُلحق بآخر الصفحة.
     */
    static String html(String id, String type, String origin, String extra) {
        boolean video = "v".equals(type);
        String src = video ? "videoId:'" + id + "'," : "";
        String vars = video
                ? "loop:1,playlist:'" + id + "',"
                : "listType:'playlist',list:'" + id + "',loop:1,";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;position:relative}"
                + "#p{width:100%;height:100%}"
                + "#m{display:none;position:absolute;left:8%;right:8%;top:30%;z-index:9;padding:18px;"
                + "border-radius:12px;background:rgba(0,0,0,.85);color:#fff;font:20px/1.8 sans-serif;"
                + "text-align:center;direction:rtl;pointer-events:none}"
                + "#d{position:absolute;left:0;right:0;bottom:0;z-index:9;padding:8px;background:rgba(0,0,0,.85);"
                + "color:#fff;font:16px/1.6 sans-serif;text-align:center;direction:rtl;pointer-events:none}"
                + "</style></head><body><div id=\"p\"></div><div id=\"m\"></div><div id=\"d\"></div>"
                + "<script src=\"https://www.youtube.com/iframe_api\" onerror=\"window.apiFail=1\"></script>"
                + "<script>"
                + "var player,ready=false,errs=0,hold=false,again=false,played=false;"
                // إن لم يعمل الفيديو نُظهر السبب وإصدار المحرك (القديم لا يشغّل يوتيوب)
                + "var ff=navigator.userAgent.match(/Firefox\\/(\\d+)/);"
                + "var engine=ff?'محرك Firefox':'إصدار WebView في هذه الشاشة';"
                + "var ver=(ff||navigator.userAgent.match(/Chrome\\/(\\d+)/)||[0,'غير معروف'])[1];"
                + "function msg(t){var d=document.getElementById('m');"
                + "d.innerHTML=t?t+'<br>'+engine+': '+ver:'';d.style.display=t?'block':'none';}"
                + "setTimeout(function(){if(!played&&!hold)msg(ready?'لم يبدأ الفيديو':'لم يتحمّل مشغّل يوتيوب');},30000);"
                // سطر تشخيص يبقى حتى يبدأ الفيديو: أين توقف التحميل، وهل تصل الشبكة إلى يوتيوب
                + "var t0=Date.now(),net={};"
                + "function probe(n,u){if(!window.fetch)return;net[n]='…';"
                + "fetch(u,{mode:'no-cors',cache:'no-store'}).then(function(){net[n]='✓';},function(){net[n]='✗';});}"
                + "probe('youtube','https://www.youtube.com/generate_204');"
                + "probe('ytimg','https://i.ytimg.com/generate_204');"
                + "function diag(){var d=document.getElementById('d');"
                + "if(played){d.style.display='none';return;}"
                + "var s=window.apiFail?'1: تعذّر تحميل مكتبة يوتيوب'"
                + ":!(window.YT&&YT.Player)?'2: بانتظار مكتبة يوتيوب'"
                + ":!ready?'3: وصلت المكتبة، بانتظار المشغّل'"
                + ":'4: المشغّل جاهز، حالة الفيديو '+st();"
                + "var n='';for(var k in net)n+=' | '+k+' '+net[k];"
                + "d.textContent='مرحلة '+s+' | '+Math.round((Date.now()-t0)/1000)+' ثانية'+n+' | '+engine+': '+ver;}"
                + "setInterval(diag,1000);diag();"
                // الأذان: إيقاف مؤقت ثم إكمال (لا يُستأنف ما أوقفه المستخدم بنفسه)
                + "function st(){try{return ready?player.getPlayerState():-1;}catch(x){return -1;}}"
                + "function halt(){try{if(ready)player.pauseVideo();}catch(x){}}"
                + "function adhanHold(){if(hold)return;hold=true;again=st()!==2;halt();}"
                + "function adhanRelease(){if(!hold)return;hold=false;"
                + "if(again){try{if(ready)player.playVideo();}catch(x){}}}"
                + "setInterval(function(){var s=st();if(hold&&(s===1||s===3))halt();},400);"
                // أوامر الريموت
                + "function cmd(c){var a=c.split(':'),k=a[0];"
                + "if(k==='hold'){adhanHold();return;}if(k==='release'){adhanRelease();return;}"
                + "if(!player||!ready)return;"
                + "try{if(k==='toggle'){if(st()===1)player.pauseVideo();else player.playVideo();}"
                + "else if(k==='play')player.playVideo();"
                + "else if(k==='pause')player.pauseVideo();"
                + "else if(k==='back')player.seekTo(Math.max(0,player.getCurrentTime()-10),true);"
                + "else if(k==='forward')player.seekTo(player.getCurrentTime()+10,true);"
                + "else if(k==='prev')player.previousVideo();"
                + "else if(k==='next')player.nextVideo();"
                + "else if(k==='at'){errs=0;player.playVideoAt(+a[1]);}}catch(x){}}"
                // {عدد فيديوهات القائمة, رقم الحالي}
                + "function info(){if(!player||!ready)return null;"
                + "var l=player.getPlaylist();return [l?l.length:0,player.getPlaylistIndex()];}"
                // إطار المشغّل بمقاس الشاشة تماماً: يوتيوب يحافظ على أبعاد الفيديو دون قص أو تمطيط
                + "function fit(){var f=document.querySelector('iframe');if(!f)return;"
                + "f.style.position='absolute';f.style.left='0';f.style.top='0';"
                + "f.style.width='100%';f.style.height='100%';}"
                + "window.addEventListener('resize',fit);"
                + "function onYouTubeIframeAPIReady(){"
                + "player=new YT.Player('p',{width:'100%',height:'100%'," + src
                + "playerVars:{" + vars + "autoplay:1,playsinline:1,rel:0,enablejsapi:1,"
                + "origin:'" + origin + "',widget_referrer:'" + origin + "'},"
                + "events:{"
                + "onReady:function(e){ready=true;fit();if(!hold)e.target.playVideo();setTimeout(fit,500);},"
                + "onStateChange:function(e){fit();if(hold){if(e.data===1||e.data===3)halt();return;}"
                + "if(e.data===1){errs=0;played=true;msg('');if(window.onPlaying)onPlaying();}"
                + (video ? "if(e.data===0){try{player.seekTo(0);player.playVideo();}catch(x){}}" : "")
                + "},"
                + "onError:function(e){if(hold)return;msg('خطأ يوتيوب رقم '+e.data);errs++;if(errs<60){try{player.nextVideo();}catch(x){}}}"
                + "}});}"
                + "</script>"
                + extra
                + "</body></html>";
    }

    /**
     * قناة الأوامر لمشغّل Firefox: لا يستطيع التطبيق تنفيذ JavaScript داخله مباشرة،
     * فتسأل الصفحة الخادم المحلي (PlayerServer) عن الأوامر وترسل له حالتها.
     * gen: رقم الصفحة، كي تُهمل رسائل صفحة سابقة.
     */
    static String remoteScript(int gen) {
        return "<script>(function(){var G=" + gen + ";"
                + "function send(p){var x=new XMLHttpRequest();x.open('GET',p);x.send();}"
                + "window.onPlaying=function(){send('s?g='+G+'&k=playing');};"
                + "send('s?g='+G+'&k=alive');"
                + "function run(c){if(c==='episodes'){var i=null;try{i=info();}catch(x){}i=i||[0,0];"
                + "send('e?g='+G+'&c='+(+i[0]||0)+'&i='+(+i[1]||0));return;}"
                + "cmd(c);if(c==='hold')send('s?g='+G+'&k=held');}"
                + "function poll(){var x=new XMLHttpRequest();x.open('GET','c?g='+G+'&t='+Date.now());x.timeout=30000;"
                + "x.onload=function(){if(x.status!==200||x.responseText==='gone')return;"
                + "x.responseText.split('\\n').forEach(function(c){if(c){try{run(c);}catch(e){}}});poll();};"
                + "x.onerror=x.ontimeout=function(){setTimeout(poll,1000);};x.send();}"
                + "poll();})();</script>";
    }
}
