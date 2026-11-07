# Ladepilot

Saját töltőár-térkép Teslához: minden szolgáltató töltője Ausztriában (és a Supercharger-ek Németországban) aktuális árral, teljesítménnyel és élő foglaltsággal.

- **Weboldal:** https://ladepilot.pages.dev
- **Adatforrások:** E-Control Ladestellenverzeichnis (ladestellen.at), Mobilithek (AFIR, CC0)

## Felépítés

```
pipeline/build.py              letölti és kicsi JSON-ba sűríti az adatokat
.github/workflows/data.yml     15 percenként lefut, az eredményt Cloudflare KV-ba tölti
.github/workflows/deploy.yml   a weboldalt telepíti Cloudflare Pages-re (minden módosításkor)
site/public/index.html         a térkép
site/public/test.html          API-tesztoldal (hibakereséshez)
site/public/data/de_tesla.json német Supercharger-ek napszakos árakkal (pillanatkép)
site/functions/api/[name].js   /api/at → a KV-ban tárolt friss adat
```

## Beállítás (egyszer kell)

### 1. Cloudflare

1. **KV-tár létrehozása:** bal menü → *Storage & Databases* → *KV* → **Create** (vagy *Create namespace*). Név: `ladepilot-data`. Utána másold ki az **ID**-t (hosszú betű-szám sor).
2. **API-token:** jobb felső sarok, profil ikon → *My Profile* → *API Tokens* → **Create Token** → lent *Create Custom Token* → **Get started**.
   - Token name: `ladepilot-github`
   - Permissions (két sor, a „+ Add more” gombbal):
     - `Account` · `Cloudflare Pages` · `Edit`
     - `Account` · `Workers KV Storage` · `Edit`
   - Account Resources: `Include` · a saját fiókod
   - **Continue to summary** → **Create Token** → másold ki a tokent (csak egyszer látszik!).
3. **Account ID:** *Workers & Pages* oldalon jobb oldalt (*Account ID*), vagy a böngésző címsorában a `dash.cloudflare.com/` utáni hosszú azonosító.

### 2. GitHub

1. **Új tároló:** jobb fent **+** → *New repository* → név: `ladepilot` → **Public** → *Create repository*.
2. **Titkok:** a tárolóban *Settings* → *Secrets and variables* → *Actions* → **New repository secret**. Négy darab:

   | Name | Érték |
   |---|---|
   | `ECONTROL_API_KEY` | az E-Control API-kulcsod |
   | `CLOUDFLARE_API_TOKEN` | az 1.2-ben készült token |
   | `CLOUDFLARE_ACCOUNT_ID` | az 1.3-ban kimásolt azonosító |
   | `KV_NAMESPACE_ID` | az 1.1-ben kimásolt KV ID |

3. **Fájlok feltöltése:** a tároló főoldalán *uploading an existing file* link (vagy *Add file* → *Upload files*). Húzd be a kicsomagolt mappa **teljes tartalmát** (a `.github`, `pipeline`, `site` mappákat és a `README.md`-t) → **Commit changes**.
4. **Első futtatás:** *Actions* fül → ha kéri, *I understand… enable them* →
   - bal oldalt **Adatok frissítése** → *Run workflow* → zöld *Run workflow*. Pár perc, zöld pipa.
   - bal oldalt **Weboldal telepítése** → ha nem futott le magától zölden, *Run workflow*.
5. Nyisd meg: **https://ladepilot.pages.dev**

Ezután az adatok maguktól frissülnek 15 percenként (a GitHub ütemezése néha késik pár percet).

## E-Control felhasználási feltételek

Az élesítés után értesíteni kell az E-Controlt az oldal címéről, a forrást fel kell tüntetni (a térkép alján szerepel), és negyedévente látogatottsági számot kérnek.
