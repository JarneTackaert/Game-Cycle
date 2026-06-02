const DATA_URL = "https://raw.githubusercontent.com/JarneTackaert/Game-Cycle/main/data/riders.csv";

// Build the rider slug used by the cyclingfantasy.cc embed.
// "Tadej Pogačar" -> "tadej-pogacar" (lowercase, accents stripped, hyphenated).
function makeSlug(name) {
    return (name || "")
        .normalize("NFD").replace(/[\u0300-\u036f]/g, "")  // strip accents
        .toLowerCase().trim()
        .replace(/[^a-z0-9]+/g, "-")                        // non-alnum -> hyphen
        .replace(/^-+|-+$/g, "");                           // trim stray hyphens
}

// Age bands of 5: 27 -> "25-29". Empty string if age missing.
function ageBand(age) {
    if (!age || isNaN(age)) return "";
    const lo = Math.floor(age / 5) * 5;
    return lo + "-" + (lo + 4);
}

// CSV uses M/W; the game's tabs expect Male/Female.
function normGender(g) {
    if (g === "M") return "Male";
    if (g === "W" || g === "F") return "Female";
    return g;
}

// Map one CSV row -> the rider object shape the game uses.
function toRider(row) {
    const age = parseInt(row["Age"], 10) || null;
    return {
        fullName: row["Rider"],                 // game reads .fullName everywhere
        slug: makeSlug(row["Rider"]),       // for the embed
        age: age,
        ageBand: ageBand(age),                 // green/red compares this
        nationality: row["Nationality"],
        team: row["Team"],
        circuit: row["Team tier"],             // WorldTour / ProTour
        specialty: row["Specialty 1"] || row["Specialty 2"] || "",
        gender: normGender(row["Gender"]),    // M/W -> Male/Female
        uciRank: parseInt(row["UCI rank"], 10) || null,  // top-250 ranked riders; null = unranked
        // hint data:
        wins: parseInt(row["Wins"], 10) || 0,
        topResults: [row["Top result 1"], row["Top result 2"], row["Top result 3"]].filter(Boolean),
        previousTeams: [
            row["Previous team 1"], row["Previous team 2"], row["Previous team 3"],
            row["Previous team 4"], row["Previous team 5"], row["Previous team 6"],
            row["Previous team 7"], row["Previous team 8"], row["Previous team 9"],
            row["Previous team 10"], row["Previous team 11"], row["Previous team 12"],
            row["Previous team 13"], row["Previous team 14"],
        ].filter(Boolean),
    };
}

let RIDERS = [];   // 'let' — assigned after fetch

async function loadRiders() {
    const res = await fetch(DATA_URL);
    if (!res.ok) throw new Error(`Data fetch failed: ${res.status}`);
    const text = await res.text();
    const parsed = Papa.parse(text, {header: true, skipEmptyLines: true});
    RIDERS = parsed.data.map(toRider).filter(r => r.fullName);
    // Stable order so the daily puzzle stays consistent across scrapes:
    RIDERS.sort((a, b) => a.fullName.localeCompare(b.fullName));
}

let ALL = [], POOL = [], pool = 'Male', answer, guesses = [], activeIdx = -1, filtered = [];
let mode = 'daily';            // 'daily' or 'practice'
let rankFilter = 'all';        // 'all' or 'ranked' (top-250-per-gender; 500 for All)
let streak = 0;                // consecutive days solved (session mock; real value comes from backend/login)
let lastSolvedDay = null;      // date-string of last daily solve, to gate streak + one-solve-per-day
let gaveUpDay = null;          // date-string of a daily give-up, to lock the round for that day
let totalFound = 0;            // riders found all-time (session mock)
/* Remembers resolved daily rounds for this session, keyed by gender×rank, so switching
   modes/tabs and returning can't reset a give-up or re-open a solved round.
   Shape: dailyResolved[dailyTag()] = {date, outcome:'solved'|'gaveup', rider, guesses} */
let dailyResolved = {};

/* ---- Daily puzzle: same rider for everyone on a given date, per pool ----
   Deterministic seed from the date so the "daily standings" are comparable.
   In the real app/site the date + pool seed lives server-side; this mirrors it. */
function todayKey() {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

function dayKeyFrom(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

function yesterdayKey() {
    const d = new Date();
    d.setDate(d.getDate() - 1);
    return dayKeyFrom(d);
}

// Each gender×rank combination is its own daily puzzle.
function dailyTag() {
    return pool + '|' + rankFilter;
}

function seededIndex(seedStr, len) {
    let h = 2166136261;
    for (let i = 0; i < seedStr.length; i++) {
        h ^= seedStr.charCodeAt(i);
        h = Math.imul(h, 16777619);
    }
    return Math.abs(h) % len;
}

/* ---- Real scoreboard data ----
   The real boards will be fetched from the server. */
let REAL_GENERAL = [];
let REAL_DAILY = [];

async function fetchRankings() {
    const fd = new FormData();
    fd.append('action', 'gc_get_rankings');
    try {
        const res = await fetch(cycleGameData.ajax_url, {
            method: 'POST',
            body: fd
        });
        const data = await res.json();
        if (data.success) {
            const myName = cycleGameData.current_user_name;
            REAL_GENERAL = data.data.general.map(p => ({...p, me: p.name === myName}));
            REAL_DAILY = data.data.daily.map(p => ({...p, me: p.name === myName}));
        }
    } catch (e) {
        console.error("Failed to fetch rankings", e);
    }
}

function load() {
    ALL = RIDERS;
    const dayLabel = document.getElementById('dayLabel');
    if (dayLabel) {
        dayLabel.textContent = todayKey();
    }
    renderStreak();
    setPool('Male');
    fetchRankings().then(renderBoards);
}

function setMode(m) {
    mode = m;
    document.getElementById('m-daily').classList.toggle('on', m === 'daily');
    document.getElementById('m-practice').classList.toggle('on', m === 'practice');
    document.getElementById('m-daily').setAttribute('aria-selected', m==='daily');
    document.getElementById('m-practice').setAttribute('aria-selected', m==='practice');
    // Clear shared UI residue from the previous mode before starting/restoring.
    // (daily and practice share the same win panel, answer var, and input.)
    document.getElementById('win').classList.remove('show');
    document.getElementById('embed').innerHTML = '';
    document.getElementById('rows').innerHTML = '';
    document.getElementById('hints').innerHTML = '';
    document.getElementById('hintProgress').innerHTML = '';
    const inp = document.getElementById('guess');
    inp.value = '';
    inp.disabled = false;
    newGame();
}

function cols() {
    const base = [
        {key: 'fullName', label: 'Renner', w: '1fr'},
        {key: 'ageBand', label: 'Leeftijd', w: '1fr'},
        {key: 'nationality', label: 'Nationaliteit', w: '1fr'},
        {key: 'team', label: 'Team', w: '1fr'},
        {key: 'circuit', label: 'Circuit', w: '1fr'},
        {key: 'specialty', label: 'Specialiteit', w: '1fr'},
    ];
    if (pool === 'All') base.push({key: 'gender', label: 'Geslacht', w: '1fr'});
    return base;
}

function applyGrid() {
    const tmpl = cols().map(c => c.w === '1fr' ? 'minmax(0,1fr)' : c.w).join(' ');
    document.querySelectorAll('.row').forEach(r => r.style.gridTemplateColumns = tmpl);
}

function renderHead() {
    document.getElementById('head').innerHTML = cols().map(c => '<div class="cell">' + c.label + '</div>').join('');
}

/* Build POOL from the current gender selection (pool) and rank filter.
   - gender: Male/Female narrows to that gender; All keeps everyone.
   - rankFilter 'ranked' keeps only riders with a UCI rank (top 250 per gender,
     so All + ranked = the 500 ranked riders). */
function buildPool() {
    let p = (pool === 'All') ? ALL : ALL.filter(r => r.gender === pool);
    if (rankFilter === 'ranked') p = p.filter(r => r.uciRank != null);
    POOL = p;
}

function setPool(g) {
    pool = g;
    ['Male', 'Female', 'All'].forEach(x => document.getElementById('t-' + x).classList.toggle('on', x === g));
    buildPool();
    renderHead();
    newGame();
}

function setRankFilter(f) {
    rankFilter = f;
    document.getElementById('r-all').classList.toggle('on', f === 'all');
    document.getElementById('r-ranked').classList.toggle('on', f === 'ranked');
    buildPool();
    newGame();
}

function newGame() {
    if (!POOL.length) return;

    // In daily mode, if today's round for THIS pool (gender×rank) is already
    // resolved, restore it (don't start a new round — that's how the give-up
    // lock was being bypassed).
    if (mode === 'daily') {
        const rec = dailyResolved[dailyTag()];
        if (rec && rec.date === todayKey()) {
            restoreResolvedDaily(rec);
            return;
        }
    }

    if (mode === 'daily') {
        answer = POOL[seededIndex(todayKey() + '|' + pool + '|' + rankFilter, POOL.length)];
    } else {
        answer = POOL[Math.floor(Math.random() * POOL.length)];
    }
    guesses = [];
    document.getElementById('rows').innerHTML = '';
    document.getElementById('hints').innerHTML = '';
    document.getElementById('hintProgress').innerHTML = '';
    document.getElementById('win').classList.remove('show');
    document.getElementById('embed').innerHTML = '';
    const inp = document.getElementById('guess');
    inp.value = '';
    inp.disabled = false;
    applyGrid();
    renderBoards();
    closeDrop();
    updateGiveUp(false);
}

/* Rebuild the finished panel for an already-resolved daily round (solved or gave up).
   Input stays locked and the give-up button stays hidden, regardless of mode-switching. */
function restoreResolvedDaily(rec) {
    answer = rec.rider;
    guesses = [];
    document.getElementById('rows').innerHTML = '';
    document.getElementById('hints').innerHTML = '';
    document.getElementById('hintProgress').innerHTML = '';
    document.getElementById('embed').innerHTML = '';
    const inp = document.getElementById('guess');
    inp.value = '';
    inp.disabled = true;
    if (rec.outcome === 'solved') {
        document.getElementById('winTitle').textContent = 'Opgelost!';
        document.getElementById('winp').textContent = rec.rider.fullName + ' (' + rec.rider.team + ') — je had ' + rec.guesses + ' gok' + (rec.guesses > 1 ? 'ken' : '') + 'nodig.';
    } else {
        document.getElementById('winTitle').textContent = 'Het antwoord was…';
        document.getElementById('winp').textContent = rec.rider.fullName + ' (' + rec.rider.team + '). Geen zorgen — betere geluk morgen.';
    }
    showEmbed();
    document.getElementById('win').classList.add('show');
    document.getElementById('counter').textContent = (rec.outcome === 'solved')
        ? "Je hebt de oplossing van vandaag al gevonden. Kom morgen terug of wissel naar oefenmodus."
        : "Je hebt opgegeven vandaag. Kom morgen terug of wissel naar oefenmodus om verder te spelen.";
    applyGrid();
    renderBoards();
    closeDrop();
    document.getElementById('giveup').classList.add('hide');
}

/* Give-up button: only in daily mode, only while the round is live (not solved/given-up today) */
function updateGiveUp(roundOver) {
    const btn = document.getElementById('giveup');
    const rec = dailyResolved[dailyTag()];
    const dailyDone = (mode === 'daily' && rec && rec.date === todayKey());
    if (mode === 'daily' && !dailyDone && !roundOver) {
        btn.classList.remove('hide');
        btn.disabled = false;
    } else {
        btn.classList.add('hide');
    }
}

function renderStreak() {
    const streakNum = document.getElementById('streakNum');
    if (streakNum) {
        streakNum.textContent = streak;
    }
}

function renderBoards() {
    // Play again only makes sense in practice; daily has one rider per day
    document.getElementById('againBtn').style.display = (mode === 'practice') ? '' : 'none';

    // GENERAL
    const gen = REAL_GENERAL;
    document.getElementById('genBoard').innerHTML = gen.map((p, i) =>
        '<div class="sbrow gen' + (p.me ? ' me' : '') + '">' +
        '<span class="rank' + (i < 3 ? ' top' : '') + '">' + (i + 1) + '</span>' +
        '<span class="sbname">' + p.name + '</span>' +
        '<span class="sbval">' + p.found + '</span>' +
        '<span class="sbflame">🔥 ' + (p.streak || 0) + '</span>' +
        '</div>').join('');

    // DAILY
    const day = REAL_DAILY;
    document.getElementById('dayBoard').innerHTML = day.map((p, i) =>
        '<div class="sbrow day' + (p.me ? ' me' : '') + '">' +
        '<span class="rank' + (i < 3 ? ' top' : '') + '">' + (i + 1) + '</span>' +
        '<span class="sbname">' + p.name + '</span>' +
        '<span class="sbval">' + p.g + ' <span class="u">beurt' + (p.g > 1 ? 'en' : '') + '</span></span>' +
        '</div>').join('');
}

let lastDailyGuesses = 0;

function cellHTML(val, match) {
    return '<div class="cell ' + (match ? 'g-green' : 'g-red') + '">' + val + '</div>';
}

function rowHTML(rider) {
    return cols().map(c => cellHTML(rider[c.key],
        c.key === 'fullName' ? rider.fullName === answer.fullName : rider[c.key] === answer[c.key]
    )).join('');
}

const EMBED_FALLBACK_HEIGHT = 270;

function showEmbed() {
    const s = answer.slug;
    document.getElementById('embed').innerHTML =
        '<div style="max-width:200px">' +
        '<div style="display:flex;align-items:center;justify-content:flex-end;gap:4px;padding:4px 8px">' +
        '<a href="https://cyclingfantasy.cc/rider/' + s + '" target="_blank" rel="noopener noreferrer" style="display:flex;align-items:center;gap:4px;text-decoration:none">' +
        '<span style="font-size:9px;font-style:italic;font-weight:bold;color:#595959">Powered by cyclingfantasy.cc</span></a></div>' +
        '<iframe id="cf-embed" src="https://cyclingfantasy.cc/embed/rider/' + s + '?theme=light" width="100%" height="' + EMBED_FALLBACK_HEIGHT + '" frameborder="0" scrolling="no" style="display:block"></iframe>' +
        '</div>';
    /* iframe-resizer: works if cyclingfantasy's embed runs the child script.
       Wrapped in try/catch so a non-cooperating embed never breaks the page. */
    try {
        if (window.iframeResize) {
            window.iframeResize({license: 'GPLv3', checkOrigin: false, waitForLoad: true}, '#cf-embed');
        }
    } catch (e) {
    }
}

/* Dynamic resize: if cyclingfantasy's embed posts its content height, apply it.
   Falls back silently to EMBED_FALLBACK_HEIGHT if no message ever arrives. */
function readHeight(data) {
    if (typeof data === 'number' && isFinite(data)) return data;
    if (typeof data === 'string') {
        try {
            data = JSON.parse(data);
        } catch (e) {
            return null;
        }
    }
    if (data && typeof data === 'object') {
        const h = data.height ?? data.h ?? (data.payload && data.payload.height);
        if (typeof h === 'number' && isFinite(h)) return h;
        if (typeof h === 'string' && h.trim() && isFinite(+h)) return +h;
    }
    return null;
}

window.addEventListener('message', function (e) {
    if (typeof e.origin === 'string' && e.origin.indexOf('cyclingfantasy.cc') === -1) return;
    const iframe = document.getElementById('cf-embed');
    if (!iframe || e.source !== iframe.contentWindow) return;
    const h = readHeight(e.data);
    if (h && h > 50) iframe.style.height = Math.ceil(h) + 'px';
});

function submit(rider) {
    if (guesses.find(x => x.fullName === rider.fullName)) return;
    guesses.unshift(rider);
    const r = document.createElement('div');
    r.className = 'row';
    r.style.gridTemplateColumns = cols().map(c => c.w === '1fr' ? 'minmax(0,1fr)' : c.w).join(' ');
    r.innerHTML = rowHTML(rider);
    document.getElementById('rows').prepend(r);
    const inp = document.getElementById('guess');
    inp.value = '';
    closeDrop();
    renderHints();
    if (rider.fullName === answer.fullName) {
        document.getElementById('winTitle').textContent = 'Solved!';
        document.getElementById('winp').textContent = answer.fullName + ' (' + answer.team + ') — solved in ' + guesses.length + ' guess' + (guesses.length > 1 ? 'es' : '') + '.';
        showEmbed();
        document.getElementById('win').classList.add('show');
        inp.disabled = true;
        document.getElementById('giveup').classList.add('hide');
        window.scrollTo({top: 0, behavior: 'smooth'});
        fireConfetti();
        recordWin();
    }
}

/* Update streak + totals on a solve. Daily mode only counts once per day and
   drives the streak; practice solves add to "found" but never the streak.
   (Real persistence happens server-side via login; this is the session mock.) */
function recordWin() {
    totalFound++;
    if (mode === 'daily' && lastSolvedDay !== todayKey()) {
        // continue the streak only if the previous solve was yesterday; otherwise a day was missed → restart at 1
        streak = (lastSolvedDay === yesterdayKey()) ? streak + 1 : 1;
        lastSolvedDay = todayKey();
        lastDailyGuesses = guesses.length;
        dailyResolved[dailyTag()] = {date: todayKey(), outcome: 'solved', rider: answer, guesses: guesses.length};

        // Save score to server
        const fd = new FormData();
        fd.append('action', 'gc_save_score');
        fd.append('score', guesses.length);
        fd.append('tijd', 0); // Tijd tracking not implemented in this version of game-cycle
        fetch(cycleGameData.ajax_url, { method: 'POST', body: fd })
            .then(() => fetchRankings())
            .then(() => renderBoards());
    }
    renderStreak();
    renderBoards();
}

/* Give up: reveal the answer, break the streak, and do NOT count toward rankings.
   No confetti, no totalFound increment, no daily-board entry. Locks the day. */
function giveUp() {
    const inp = document.getElementById('guess');
    inp.disabled = true;
    document.getElementById('giveup').classList.add('hide');
    if (mode === 'daily') {
        gaveUpDay = todayKey();
        streak = 0;            // giving up always breaks the streak
        dailyResolved[dailyTag()] = {date: todayKey(), outcome: 'gaveup', rider: answer, guesses: guesses.length};
        renderStreak();
    }
    document.getElementById('winTitle').textContent = 'The answer was…';
    document.getElementById('winp').textContent = answer.fullName + ' (' + answer.team + '). No worries — better luck tomorrow.';
    showEmbed();
    document.getElementById('win').classList.add('show');
    window.scrollTo({top: 0, behavior: 'smooth'});
    renderBoards();
}

const CONFETTI_COLORS = ['#d6232a', '#e2574b', '#2ea043', '#f0a830', '#ffffff'];

function fireConfetti() {
    if (typeof confetti === 'function') {
        const end = Date.now() + 1200;
        (function frame() {
            confetti({particleCount: 5, angle: 60, spread: 55, origin: {x: 0}, colors: CONFETTI_COLORS});
            confetti({particleCount: 5, angle: 120, spread: 55, origin: {x: 1}, colors: CONFETTI_COLORS});
            if (Date.now() < end) requestAnimationFrame(frame);
        })();
        confetti({particleCount: 120, spread: 80, origin: {y: 0.3}, colors: CONFETTI_COLORS});
    } else {
        fallbackConfetti();
    }
}

/* Self-contained confetti — runs when the CDN library isn't available
   (offline, ad-blocker, CSP, or not yet loaded). No dependencies. */
function fallbackConfetti() {
    let c = document.getElementById('cf-canvas');
    if (!c) {
        c = document.createElement('canvas');
        c.id = 'cf-canvas';
        c.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:9999';
        document.body.appendChild(c);
    }
    const ctx = c.getContext('2d');

    function size() {
        c.width = window.innerWidth;
        c.height = window.innerHeight;
    }

    size();
    const N = 160, parts = [];
    for (let i = 0; i < N; i++) {
        parts.push({
            x: Math.random() * c.width,
            y: -20 - Math.random() * c.height * 0.3,
            r: 4 + Math.random() * 5,
            vx: (Math.random() - 0.5) * 3,
            vy: 2 + Math.random() * 4,
            rot: Math.random() * Math.PI,
            vr: (Math.random() - 0.5) * 0.3,
            color: CONFETTI_COLORS[i % CONFETTI_COLORS.length]
        });
    }
    const start = Date.now(), dur = 2600;
    (function tick() {
        const t = Date.now() - start;
        ctx.clearRect(0, 0, c.width, c.height);
        parts.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;
            p.vy += 0.05;
            p.rot += p.vr;
            ctx.save();
            ctx.translate(p.x, p.y);
            ctx.rotate(p.rot);
            ctx.fillStyle = p.color;
            ctx.globalAlpha = Math.max(0, 1 - t / dur);
            ctx.fillRect(-p.r / 2, -p.r / 2, p.r, p.r * 0.6);
            ctx.restore();
        });
        if (t < dur) requestAnimationFrame(tick);
        else ctx.clearRect(0, 0, c.width, c.height);
    })();
}

const input = document.getElementById('guess'), drop = document.getElementById('drop');
input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase();
    if (!q) {
        closeDrop();
        return;
    }
    filtered = POOL.filter(r => (r.fullName.toLowerCase().includes(q) || r.team.toLowerCase().includes(q)) && !guesses.find(g => g.fullName === r.fullName))
        .sort((a, b) => {
            const an = a.fullName.toLowerCase(), bn = b.fullName.toLowerCase();
            const aNameHit = an.includes(q), bNameHit = bn.includes(q);
            // name matches rank above team-only matches
            if (aNameHit !== bNameHit) return aNameHit ? -1 : 1;
            if (aNameHit) {
                const ap = an.startsWith(q), bp = bn.startsWith(q);
                if (ap !== bp) return ap ? -1 : 1;
                return an.localeCompare(bn);
            }
            // both are team-only matches: group by team, then rider name
            const at = a.team.toLowerCase(), bt = b.team.toLowerCase();
            return at.localeCompare(bt) || an.localeCompare(bn);
        }).slice(0, 8);
    if (!filtered.length) {
        closeDrop();
        return;
    }
    activeIdx = -1;
    drop.innerHTML = filtered.map((r, i) =>
        '<div class="opt" data-i="' + i + '"><span class="nm">' + r.fullName + '</span><span class="meta">' + r.team + '<br>' + r.nationality + '</span></div>').join('');
    drop.classList.add('show');
    drop.querySelectorAll('.opt').forEach(o => o.onclick = () => submit(filtered[+o.dataset.i]));
});
input.addEventListener('keydown', e => {
    const opts = drop.querySelectorAll('.opt');
    if (e.key === 'ArrowDown') {
        activeIdx = Math.min(activeIdx + 1, opts.length - 1);
        paint(opts);
        e.preventDefault();
    } else if (e.key === 'ArrowUp') {
        activeIdx = Math.max(activeIdx - 1, 0);
        paint(opts);
        e.preventDefault();
    } else if (e.key === 'Enter') {
        if (activeIdx >= 0 && filtered[activeIdx]) submit(filtered[activeIdx]); else if (filtered.length === 1) submit(filtered[0]);
    }
});

function paint(opts) {
    opts.forEach((o, i) => o.classList.toggle('active', i === activeIdx));
}

function closeDrop() {
    drop.classList.remove('show');
    drop.innerHTML = '';
    activeIdx = -1;
}

document.addEventListener('click', e => {
    if (!e.target.closest('.searchbox')) closeDrop();
});


function renderHints() {
    var el = document.getElementById('hints');
    el.innerHTML = '';
    if (!answer) return;
    var wrong = guesses.filter(function (g) {
        return g.fullName !== answer.fullName
    }).length;
    if (wrong >= 3) {
        var w = answer.wins || 0;
        var txt = w > 0 ? '\u{1F3C6} Deze renner heeft <b>' + w + '</b> ' + (w === 1 ? 'overwinning' : 'overwinningen') + '.'
            : '\u{1F3C6} Deze renner heeft <b>geen geregistreerde overwinningen</b>.';
        el.innerHTML += '<div class="hint hint-victories">' + txt + '</div>';
    }
    if (wrong >= 5) {
        var tops = answer.topResults || [];
        var txt2 = '\u{1F947} <b>Top resultaten:</b> ';
        if (tops.length > 0) txt2 += '<ul>' + tops.map(function (t) {
            return '<li>' + t + '</li>'
        }).join('') + '</ul>';
        else txt2 += 'Geen top resultaten opgenomen.';
        el.innerHTML += '<div class="hint hint-results">' + txt2 + '</div>';
    }
    if (wrong >= 8) {
        var teams = answer.previousTeams || [];
        var txt3 = '\u{1F504} <b>Voorgaande teams:</b> ';
        if (teams.length > 0) {
            txt3 += '<span class="team-chain">' + teams.map(function (t, i) {
                return '<span class="team-chip">' + t + '</span>' + (i < teams.length - 1 ? ' <span class="team-arrow">\u2192</span> ' : '')
            }).join('') + '</span>';
        } else txt3 += 'Geen voorgaande teams opgenomen.';
        el.innerHTML += '<div class="hint hint-teams">' + txt3 + '</div>';
    }
    renderHintProgress(wrong);
}

function renderHintProgress(wrong) {
    var el = document.getElementById('hintProgress');
    if (!answer) {
        el.innerHTML = '';
        return;
    }
    var thresholds = [3, 5, 8];
    var next = null;
    for (var i = 0; i < thresholds.length; i++) {
        if (wrong < thresholds[i]) {
            next = thresholds[i];
            break;
        }
    }
    if (!next) {
        el.innerHTML = '<div class="hp-done">\u2713 Alle hints ontgrendeld</div>';
        return;
    }
    var prev = thresholds[thresholds.indexOf(next) - 1] || 0;
    var pct = Math.round(((wrong - prev) / (next - prev)) * 100);
    var remaining = next - wrong;
    el.innerHTML = '<div class="hp-label">Volgende hint in ' + remaining + ' verkeerde gok' + (remaining > 1 ? 'jes' : '') + '</div>'
        + '<div class="hp-bar"><div class="hp-fill" style="width:' + pct + '%"></div></div>';
}


// ── Startup: fetch the rider CSV first, then boot the game ──
loadRiders()
    .then(load)
    .catch(function (err) {
        console.error(err);
        document.getElementById('err').textContent =
            "Kon de renner data niet ophalen. Probeer binnen 1 minuut opnieuw.";
    });
