#!/usr/bin/env python3
"""Sonde des sites de recettes : sitemaps publiés, nombre de recettes, lisibilité des fiches.

Reconnaissance avant d'écrire la collecte du catalogue local. Ne télécharge que robots.txt, les sitemaps
et deux fiches par site, à un rythme lent. Usage : python3 tools/catalog/probe.py [id_site ...]
"""
import gzip, json, re, sys, time, urllib.request, urllib.error

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
MAX_SITEMAPS = 80   # fichiers de sitemap lus par site
PAUSE = 0.7         # secondes entre deux requêtes

# id, hôte, forme d'une adresse de recette (comme RecipeSites.kt)
SITES = [
    ("marmiton", "www.marmiton.org", r"marmiton\.org/recettes/recette_[^/]+\.aspx$"),
    ("750g", "www.750g.com", r"750g\.com/[^/]+-r\d+\.htm$"),
    ("cuisineaz", "www.cuisineaz.com", r"cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"),
    ("jdf", "cuisine.journaldesfemmes.fr", r"journaldesfemmes\.fr/recette/\d+-[^/]+$"),
    ("cuisineactuelle", "www.cuisineactuelle.fr", r"cuisineactuelle\.fr/recettes/[^/]+-\d+"),
    ("ptitchef", "www.ptitchef.com", r"ptitchef\.com/recettes/.+-fid-\d+"),
    ("ricardo", "www.ricardocuisine.com", r"ricardocuisine\.com/recettes/\d+-"),
    ("chefsimon", "chefsimon.com", r"chefsimon\.com/recettes/[^/]+$"),
    ("elle", "www.elle.fr", r"elle\.fr/Elle-a-Table/Recettes-de-cuisine/(.+/)?[^/]+-\d+$"),
    ("hellofresh", "www.hellofresh.fr", r"hellofresh\.fr/recipes/[^/]+-[0-9a-f]{24}$"),
    ("picnic", "picnic.app", r"picnic\.app/fr/recettes?/[^/]+/[^/]+$"),
    ("jow", "jow.fr", r"jow\.fr/recipes/[^/]+$"),
]
CHALLENGE = ("challenge-platform", "Just a moment", "captcha-delivery", "cf-chl-")


def fetch(url, limit=60_000_000):
    """(code, texte, erreur). Les sitemaps .gz sont décompressés."""
    time.sleep(PAUSE)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "fr-FR,fr;q=0.9", "Accept": "*/*"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            data = r.read(limit)
            if url.endswith(".gz") or data[:2] == b"\x1f\x8b":
                try:
                    data = gzip.decompress(data)
                except Exception:
                    pass
            return r.status, data.decode("utf-8", "replace"), None
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read(4000).decode("utf-8", "replace")
        except Exception:
            pass
        return e.code, body, f"HTTP {e.code}"
    except Exception as e:
        return 0, "", f"{type(e).__name__}: {e}"


def blocked(text):
    return any(m in text for m in CHALLENGE)


def probe_recipe(url):
    code, html, err = fetch(url, 3_000_000)
    out = {"url": url, "code": code, "octets": len(html), "anti_robot": blocked(html), "erreur": err}
    recipe = None
    for block in re.findall(r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>', html, re.S | re.I):
        try:
            data = json.loads(block.strip())
        except Exception:
            continue
        stack = [data]
        while stack and recipe is None:
            x = stack.pop()
            if isinstance(x, list):
                stack.extend(x)
            elif isinstance(x, dict):
                t = x.get("@type")
                if t == "Recipe" or (isinstance(t, list) and "Recipe" in t):
                    recipe = x
                else:
                    stack.extend(v for v in x.values() if isinstance(v, (dict, list)))
        if recipe:
            break
    if recipe:
        rating = recipe.get("aggregateRating") or {}
        out.update({
            "recette": recipe.get("name"),
            "ingredients": len(recipe.get("recipeIngredient") or []),
            "note": rating.get("ratingValue") if isinstance(rating, dict) else None,
            "avis": (rating.get("ratingCount") or rating.get("reviewCount")) if isinstance(rating, dict) else None,
            "categorie": recipe.get("recipeCategory"),
            "duree": recipe.get("totalTime") or recipe.get("cookTime"),
            "image": bool(recipe.get("image")),
        })
    else:
        out["recette"] = None
    return out


def probe_site(sid, host, pattern):
    rx = re.compile(pattern)
    print(f"\n## {sid} — {host}")
    code, robots, err = fetch(f"https://{host}/robots.txt")
    print(f"- robots.txt : {code}{' (' + err + ')' if err else ''}{' — page anti-robot' if blocked(robots) else ''}")
    queue = re.findall(r"(?im)^\s*sitemap:\s*(\S+)", robots)
    if not queue:
        queue = [f"https://{host}/sitemap.xml", f"https://{host}/sitemap_index.xml"]
        print("- aucun sitemap déclaré dans robots.txt : essai de /sitemap.xml et /sitemap_index.xml")
    seen, files, total, samples, newest = set(), [], 0, [], ""
    while queue and len(seen) < MAX_SITEMAPS:
        sm = queue.pop(0)
        if sm in seen:
            continue
        seen.add(sm)
        c, xml, e = fetch(sm)
        if c != 200:
            files.append((sm, f"illisible ({e or c})", 0))
            continue
        locs = re.findall(r"<loc>\s*(?:<!\[CDATA\[)?\s*([^<\]\s]+)", xml)
        if "<sitemapindex" in xml:
            # Les sous-sitemaps qui parlent de recettes d'abord.
            children = sorted(locs, key=lambda u: 0 if re.search(r"recette|recipe", u, re.I) else 1)
            queue.extend(children)
            files.append((sm, f"index de {len(locs)} sitemaps", 0))
            continue
        hits = [u for u in locs if rx.search(u.split("?")[0].split("#")[0])]
        mods = re.findall(r"<lastmod>\s*([^<\s]+)", xml)
        if mods:
            newest = max(newest, max(mods))
        total += len(hits)
        files.append((sm, f"{len(locs)} adresses, lastmod {'oui' if mods else 'non'}", len(hits)))
        if hits and len(samples) < 2:
            samples.append(hits[len(hits) // 2])
    print(f"- sitemaps lus : {len(seen)}{' (plafond atteint, il en reste ' + str(len(queue)) + ')' if queue else ''}")
    print(f"- **adresses de recettes trouvées : {total}**" + (f" — dernière modification vue : {newest}" if newest else ""))
    shown = [f for f in files if f[2] > 0][:8] + [f for f in files if f[2] == 0][:6]
    for sm, info, n in shown:
        print(f"  - `{sm}` : {info}" + (f", **{n} recettes**" if n else ""))
    if not samples:
        print("- aucune fiche à tester (pas d'adresse de recette dans les sitemaps)")
    for url in samples:
        r = probe_recipe(url)
        if r.get("recette"):
            print(f"- fiche lisible : « {r['recette']} » — {r['ingredients']} ingrédients, note {r['note']} ({r['avis']} avis), "
                  f"catégorie {r['categorie']!r}, durée {r['duree']}, image {'oui' if r['image'] else 'non'} — `{url}`")
        else:
            print(f"- fiche NON lisible : code {r['code']}, {r['octets']} octets, anti-robot {'oui' if r['anti_robot'] else 'non'}"
                  f"{', ' + r['erreur'] if r['erreur'] else ''} — `{url}`")


if __name__ == "__main__":
    wanted = set(sys.argv[1:])
    print("# Sonde des sites de recettes")
    for sid, host, pattern in SITES:
        if wanted and sid not in wanted:
            continue
        try:
            probe_site(sid, host, pattern)
        except Exception as e:  # un site en panne ne doit pas arrêter la sonde
            print(f"- erreur inattendue : {type(e).__name__}: {e}")
        sys.stdout.flush()
