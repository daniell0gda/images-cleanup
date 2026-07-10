"""Self-contained HTML for the public album-share surface (spec §3.12/§6).

Both builders return a single HTML string with all CSS/JS inlined and every
asset URL relative, so the page renders through whatever host served it without
any external dependency. Kept out of ``server.py`` so the route layer stays lean.
"""
import html


def not_found_html() -> str:
    """Friendly 404 page for unknown/revoked share tokens (never a stack trace)."""
    return (
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        "<meta name=\"robots\" content=\"noindex,nofollow\">"
        "<meta name=\"referrer\" content=\"no-referrer\">"
        "<title>Link not found</title>"
        "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;"
        "justify-content:center;font-family:system-ui,sans-serif;background:#111;"
        "color:#eee}.box{text-align:center;padding:2rem}h1{font-size:1.5rem;"
        "margin:0 0 .5rem}p{color:#aaa;margin:0}</style></head>"
        "<body><div class=\"box\"><h1>Link not found</h1>"
        "<p>This shared album link is no longer available.</p></div></body></html>"
    )


def _single_item_html(token: str, safe_name: str, item: dict) -> str:
    """Full-size view for a one-item album: the photo/video shown directly.

    A single-item album has nothing to page through, so it skips the thumbnail
    grid + lightbox entirely and renders the media full-size (image ``preview``
    or a playable ``video``). The item's ``thumb`` is kept as an instant
    blurred backdrop while the full-size bytes load. All asset URLs stay
    relative for reverse-proxy safety (§3.12).
    """
    base = f"/share/{token}/media/{item['id']}"
    if item["kind"] == "video":
        media = (
            f'<video class="single-media" controls playsinline '
            f'poster="{base}/thumb" src="{base}/stream"></video>'
        )
    else:
        media = f'<img class="single-media" src="{base}/preview" alt="">'
    return (
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        "<meta name=\"robots\" content=\"noindex,nofollow\">"
        "<meta name=\"referrer\" content=\"no-referrer\">"
        f"<title>{safe_name}</title>"
        "<style>"
        "*{box-sizing:border-box}"
        "body{margin:0;font-family:system-ui,sans-serif;background:#111;color:#eee}"
        "header{padding:1.25rem 1rem;font-size:1.4rem;font-weight:600}"
        ".single{position:relative;height:calc(100vh - 4.75rem);display:flex;"
        "align-items:center;justify-content:center;overflow:hidden}"
        ".single-blur{position:absolute;inset:0;width:100%;height:100%;"
        "object-fit:cover;filter:blur(30px) brightness(.5);transform:scale(1.1)}"
        ".single-media{position:relative;max-width:96vw;max-height:100%;"
        "object-fit:contain;display:block;border-radius:4px;"
        "box-shadow:0 8px 40px rgba(0,0,0,.5)}"
        "</style></head><body>"
        f"<header>{safe_name}</header>"
        '<div class="single">'
        f'<img class="single-blur" src="{base}/thumb" alt="" aria-hidden="true">'
        f"{media}"
        "</div>"
        "</body></html>"
    )


def page_html(token: str, name: str, items: list[dict]) -> str:
    """Self-contained HTML for a shared album: name header + thumbnail grid.

    All internal asset URLs are RELATIVE (``/share/{token}/media/{id}/...``) so
    the page resolves bytes through whatever host served it (spec §3.12). The
    header shows the album name only — never creator/device info (§6).
    """
    safe_name = html.escape(name)
    if len(items) == 1:
        return _single_item_html(token, safe_name, items[0])
    cells = []
    for item in items:
        mid = item["id"]
        base = f"/share/{token}/media/{mid}"
        if item["kind"] == "video":
            cells.append(
                f'<button class="cell video" data-kind="video" data-src="{base}/stream" '
                f'aria-label="Play video">'
                f'<img loading="lazy" src="{base}/thumb" alt="">'
                f'<span class="badge">&#9658;</span></button>'
            )
        else:
            cells.append(
                f'<button class="cell" data-kind="image" data-src="{base}/preview" '
                f'aria-label="Open photo">'
                f'<img loading="lazy" src="{base}/thumb" alt=""></button>'
            )
    grid = "".join(cells)
    empty_state = (
        '<p class="empty">This album has no photos yet.</p>' if not items else ""
    )
    return (
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        "<meta name=\"robots\" content=\"noindex,nofollow\">"
        "<meta name=\"referrer\" content=\"no-referrer\">"
        f"<title>{safe_name}</title>"
        "<style>"
        "*{box-sizing:border-box}"
        "body{margin:0;font-family:system-ui,sans-serif;background:#111;color:#eee}"
        "header{padding:1.25rem 1rem;font-size:1.4rem;font-weight:600}"
        ".grid{display:grid;gap:4px;padding:0 4px 4px;"
        # min(300px,100%) keeps a 300px floor on wide screens but never overflows
        # a viewport narrower than 300px (collapses to a single full-width column).
        "grid-template-columns:repeat(auto-fill,minmax(min(300px,100%),1fr))}"
        ".cell{position:relative;padding:0;border:0;background:#222;cursor:pointer;"
        "aspect-ratio:1/1;overflow:hidden}"
        ".cell img{width:100%;height:100%;object-fit:cover;display:block;"
        "transition:transform .25s ease}"
        ".cell:hover img{transform:scale(1.04)}"
        ".cell:focus-visible{outline:2px solid #4da3ff;outline-offset:2px}"
        ".badge{position:absolute;inset:0;display:flex;align-items:center;"
        "justify-content:center;font-size:2rem;color:#fff;"
        "text-shadow:0 1px 4px rgba(0,0,0,.6);pointer-events:none}"
        ".empty{color:#aaa;padding:1rem;text-align:center}"
        # Lightbox pager: fades via opacity+visibility (display toggles can't
        # transition); always centered, media contained, chrome floats over it.
        "#lightbox{position:fixed;inset:0;background:rgba(0,0,0,.94);z-index:1000;"
        "display:flex;align-items:center;justify-content:center;"
        "opacity:0;visibility:hidden;transition:opacity .2s ease;"
        "-webkit-user-select:none;user-select:none;touch-action:pan-y}"
        "#lightbox.open{opacity:1;visibility:visible}"
        ".lb-stage{display:flex;align-items:center;justify-content:center;"
        "max-width:100%;max-height:100%;padding:1rem}"
        ".lb-stage img,.lb-stage video{max-width:96vw;max-height:92vh;"
        "object-fit:contain;display:block;border-radius:4px;"
        "box-shadow:0 8px 40px rgba(0,0,0,.5)}"
        ".lb-btn{position:absolute;border:0;color:#fff;cursor:pointer;"
        "background:rgba(255,255,255,.12);backdrop-filter:blur(6px);"
        "display:flex;align-items:center;justify-content:center;line-height:1;"
        "transition:background .15s ease,transform .12s ease}"
        ".lb-btn:hover{background:rgba(255,255,255,.24)}"
        ".lb-btn:focus-visible{outline:2px solid #fff;outline-offset:2px}"
        ".lb-nav{top:50%;transform:translateY(-50%);width:52px;height:52px;"
        "border-radius:50%;font-size:2rem;padding-bottom:.15em}"
        ".lb-nav:active{transform:translateY(-50%) scale(.92)}"
        ".lb-prev{left:16px}.lb-next{right:16px}"
        ".lb-close{top:14px;right:16px;width:44px;height:44px;border-radius:50%;"
        "font-size:1.5rem}"
        ".lb-counter{position:absolute;bottom:18px;left:50%;"
        "transform:translateX(-50%);color:#fff;font-size:.85rem;"
        "background:rgba(0,0,0,.45);backdrop-filter:blur(6px);"
        "padding:.35rem .8rem;border-radius:999px}"
        ".lb-hidden{display:none}"
        "@media (max-width:600px){"
        ".lb-nav{width:44px;height:44px;font-size:1.6rem}"
        ".lb-prev{left:8px}.lb-next{right:8px}}"
        "</style></head><body>"
        f"<header>{safe_name}</header>"
        f'<div class="grid">{grid}</div>'
        f"{empty_state}"
        # Lightbox pager: backdrop, prev/next, close, and a position counter.
        # role=dialog + aria-modal so assistive tech treats it as a modal layer.
        '<div id="lightbox" role="dialog" aria-modal="true" aria-label="Media viewer" '
        'aria-hidden="true">'
        '<button type="button" class="lb-btn lb-close" aria-label="Close">&#10005;</button>'
        '<button type="button" class="lb-btn lb-nav lb-prev" aria-label="Previous">'
        "&#8249;</button>"
        '<div class="lb-stage"></div>'
        '<button type="button" class="lb-btn lb-nav lb-next" aria-label="Next">'
        "&#8250;</button>"
        '<div class="lb-counter" aria-live="polite"></div>'
        "</div>"
        "<script>"
        "(function(){"
        "var lb=document.getElementById('lightbox');"
        "var stage=lb.querySelector('.lb-stage');"
        "var counter=lb.querySelector('.lb-counter');"
        "var prev=lb.querySelector('.lb-prev');"
        "var next=lb.querySelector('.lb-next');"
        "var closeBtn=lb.querySelector('.lb-close');"
        "var cells=Array.prototype.slice.call(document.querySelectorAll('.cell'));"
        "var idx=-1,opener=null;"
        # Single item: nothing to page through, so hide nav + counter.
        "if(cells.length<2){prev.classList.add('lb-hidden');"
        "next.classList.add('lb-hidden');counter.classList.add('lb-hidden');}"
        "function render(){var c=cells[idx];var src=c.getAttribute('data-src');"
        "if(c.getAttribute('data-kind')==='video'){"
        "stage.innerHTML='<video controls autoplay playsinline src=\"'+src+'\"></video>';}"
        "else{stage.innerHTML='<img alt=\"\" src=\"'+src+'\">';}"
        "counter.textContent=(idx+1)+' / '+cells.length;}"
        "function open(i){idx=i;opener=cells[i];render();lb.classList.add('open');"
        "lb.setAttribute('aria-hidden','false');document.body.style.overflow='hidden';"
        "closeBtn.focus();}"
        # Stop the video, restore scroll, and return focus to the opened cell.
        "function close(){lb.classList.remove('open');"
        "lb.setAttribute('aria-hidden','true');stage.innerHTML='';"
        "document.body.style.overflow='';if(opener){opener.focus();}idx=-1;}"
        "function go(d){if(idx<0)return;idx=(idx+d+cells.length)%cells.length;render();}"
        "cells.forEach(function(c,i){c.addEventListener('click',function(){open(i);});});"
        "prev.addEventListener('click',function(e){e.stopPropagation();go(-1);});"
        "next.addEventListener('click',function(e){e.stopPropagation();go(1);});"
        "closeBtn.addEventListener('click',function(e){e.stopPropagation();close();});"
        # Click on the backdrop (not the media/chrome) closes.
        "lb.addEventListener('click',function(e){if(e.target===lb||e.target===stage)close();});"
        "document.addEventListener('keydown',function(e){"
        "if(!lb.classList.contains('open'))return;"
        "if(e.key==='Escape')close();"
        "else if(e.key==='ArrowLeft')go(-1);"
        "else if(e.key==='ArrowRight')go(1);});"
        # Horizontal swipe on touch devices, ignoring mostly-vertical drags.
        "var sx=0,sy=0;"
        "stage.addEventListener('touchstart',function(e){var t=e.changedTouches[0];"
        "sx=t.clientX;sy=t.clientY;},{passive:true});"
        "stage.addEventListener('touchend',function(e){var t=e.changedTouches[0];"
        "var dx=t.clientX-sx,dy=t.clientY-sy;"
        "if(Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy))go(dx<0?1:-1);},{passive:true});"
        "})();"
        "</script></body></html>"
    )
