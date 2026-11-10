// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * The rider's own dash screen ([CustomDashUi]): which widgets, in which order, and the accent
 * colour. The first widget is drawn large; the rest fill a two-column grid beside it.
 */
object DashLayout {
    private const val PREFS = "dash_layout"
    const val MAX_WIDGETS = 7

    enum class Widget(val label: Int) {
        SPEED(R.string.dl_w_speed),
        CLOCK(R.string.dl_w_clock),
        TRIP(R.string.dl_w_trip),
        RIDE_TIME(R.string.dl_w_ride_time),
        HEADING(R.string.dl_w_heading),
        ALTITUDE(R.string.dl_w_altitude),
        MAX_SPEED(R.string.dl_w_max_speed),
        PHONE(R.string.dl_w_phone),
        MAINTENANCE(R.string.dl_w_maintenance),
        MAP(R.string.dl_w_map),
        LEAN(R.string.dl_w_lean),
        FUEL(R.string.dl_w_fuel),
        MEDIA(R.string.dl_w_media),
    }

    /** ARGB accents the rider can pick; the first is the app's brand gold. Keep the first three in place (they pick the mode tab). */
    val ACCENTS = intArrayOf(
        0xFFD1A955.toInt(), 0xFF5BD47A.toInt(), 0xFF4FC3F7.toInt(),
        0xFFFF8A65.toInt(), 0xFFFF6B5E.toInt(), 0xFFF1F4EC.toInt(),
        0xFFF5B80C.toInt(), 0xFFFF2A2A.toInt(), 0xFFFF2E88.toInt(),
        0xFFB066FF.toInt(), 0xFF5B7CFF.toInt(), 0xFF22E5FF.toInt(),
        0xFF1DE9B6.toInt(), 0xFFC9CDD3.toInt(),
    )

    /** GRID = the widget tiles below; the rest are looks of the full gauge cluster ([ProDashView]). */
    /** Groups of templates in the editor: pick the kind first, then the dash. */
    enum class Category(val label: Int) {
        BASIC(R.string.dl_cat_basic), SPORT(R.string.dl_cat_sport), LUXURY(R.string.dl_cat_luxury),
        TOURING(R.string.dl_cat_touring), NEON(R.string.dl_cat_neon), FANTASY(R.string.dl_cat_fantasy),
        PREMIUM(R.string.dl_cat_premium),
    }

    enum class Style(val label: Int, val category: Category) {
        GRID(R.string.dl_style_grid, Category.BASIC), PRO(R.string.dl_style_pro, Category.BASIC), NAV(R.string.dl_style_nav, Category.BASIC),
        NAVC(R.string.dl_style_navc, Category.BASIC),
        SMART(R.string.dl_style_smart, Category.BASIC),
        LIME(R.string.dl_style_lime, Category.BASIC),
        NAVT(R.string.dl_style_navt, Category.BASIC),
        ADVNAV(R.string.dl_style_advnav, Category.BASIC),
        RACING(R.string.dl_style_racing, Category.SPORT), RACEBAR(R.string.dl_style_racebar, Category.SPORT), ROSSO(R.string.dl_style_rosso, Category.SPORT),
        GT(R.string.dl_style_gt, Category.SPORT), PAPAYA(R.string.dl_style_papaya, Category.SPORT),
        GOLD(R.string.dl_style_gold, Category.LUXURY), GRAND(R.string.dl_style_grand, Category.LUXURY),
        NOIR(R.string.dl_style_noir, Category.LUXURY),
        TOURING(R.string.dl_style_touring, Category.TOURING), ADVENTURE(R.string.dl_style_adventure, Category.TOURING),
        TOURNAV(R.string.dl_style_tournav, Category.TOURING),
        TERRAIN(R.string.dl_style_terrain, Category.TOURING),
        CYBER(R.string.dl_style_cyber, Category.NEON), NEONCITY(R.string.dl_style_neoncity, Category.NEON),
        NEON(R.string.dl_style_neon, Category.NEON), SPACE(R.string.dl_style_space, Category.NEON),
        DRAGON(R.string.dl_style_dragon, Category.FANTASY), ANIME(R.string.dl_style_anime, Category.FANTASY),
        ANIMAL(R.string.dl_style_animal, Category.FANTASY),
        /** Android Auto (Google Maps / Waze, its own route line) on the right, our gauge panel on the left ([AaSide]). */
        AASIDE(R.string.dl_style_aaside, Category.PREMIUM),
        /** Car-style cluster: round speedo left, Android Auto in the middle, ride info right. */
        AACLUSTER(R.string.dl_style_aacluster, Category.PREMIUM),
        /** Android Auto across the top, a slim speed + info bar along the bottom. */
        AABAR(R.string.dl_style_aabar, Category.PREMIUM),
        /** AASIDE mirrored: Android Auto left, the panel on the right ([Layout.sideFrac] wide). */
        AARIGHT(R.string.dl_style_aaright, Category.PREMIUM),
        /** The rider's sport-cluster mockup: big speed, speed bar and ride rows left, Android Auto right. */
        AASPORT(R.string.dl_style_aasport, Category.PREMIUM),
        /** The rider's round-gauge mockup: big speed arc, lean ladder, status and fuel panels left, Android Auto right. */
        AAGAUGE(R.string.dl_style_aagauge, Category.PREMIUM),
        /** The rider's orange "368G" mockup: name plate, segmented speed band, panels left, Android Auto right. */
        AARACE(R.string.dl_style_aarace, Category.PREMIUM);
        val isPro get() = this != GRID
        /** Needs [Premium] (play build): the Android Auto templates and the big-map ones. */
        val paid get() = category == Category.PREMIUM || this in BIG_MAP
        /** A template with its own palette (Cyberpunk, Dragon…), unless the rider picks colours. */
        val themed get() = this != GRID && this != PRO
    }

    /** Pieces of the PRO templates the rider can hide (the grid hides by unticking widgets). */
    enum class Part(val label: Int) {
        ART(R.string.dl_p_art), SCENE(R.string.dl_p_scene), MODE_TABS(R.string.dl_p_tabs), GPS(R.string.dl_p_gps), LIVE_MAP(R.string.dl_p_live_map), LEAN(R.string.dl_w_lean),
        TYRE(R.string.pro_tyre), PHONE_TEMP(R.string.pro_phone), BIKE_BATTERY(R.string.pro_battery), FUEL(R.string.pro_fuel),
        HEADING(R.string.pro_heading), RANGE(R.string.pro_range), MAINTENANCE(R.string.pro_maint),
        TRIP(R.string.pro_trip), ODO(R.string.pro_odo), ALTITUDE(R.string.pro_alt),
        AVG(R.string.pro_avg), RIDE_TIME(R.string.pro_time), MAX_SPEED(R.string.pro_max),
    }

    /** Dash effects (PRO templates), each with its own switch. */
    enum class Fx(val label: Int, val hint: Int) {
        INTRO(R.string.dl_fx_intro, R.string.dl_fx_intro_hint),
        SWEEP(R.string.dl_sweep, R.string.dl_sweep_hint),
        SMOOTH(R.string.dl_fx_smooth, R.string.dl_fx_smooth_hint),
        GLOW(R.string.dl_fx_glow, R.string.dl_fx_glow_hint),
    }

    /** Speed alert choices in km/h (0 = off). */
    val ALERTS = intArrayOf(0, 80, 90, 100, 110, 120)

    /**
     * [tankLiters] feeds the PRO fuel estimate (0 = unknown → no fuel gauge). The bike never
     * reports its fuel level, so the gauge is worked out from the refuel log.
     */
    data class Layout(
        val widgets: List<Widget>,
        val accent: Int,
        val style: Style = Style.GRID,
        val tankLiters: Float = 0f,
        /** End colour of a gradient from [accent]; null = a single colour. */
        val accent2: Int? = null,
        /** The rider chose colours: themed templates use [accent] / [accent2] instead of their own. */
        val customColor: Boolean = false,
        /** Hidden PRO parts; like the colours, kept per template. */
        val hidden: Set<Part> = emptySet(),
        /** Speed shown red and the frame flashing above this (km/h, 0 = off); also where the red zone starts. */
        val speedAlert: Int = 0,
        /** Darken the dash at night ([NightPrefs]). */
        val nightDim: Boolean = true,
        /** Effects that are on (all by default). */
        val effects: Set<Fx> = Fx.entries.toSet(),
        /** Navigation from the app's Map screen shows on My Dash (any template), not on the Map dash. */
        val navOnDash: Boolean = true,
        /** [Style.AASIDE]: share of the dash width the left panel takes; Android Auto gets the rest. */
        val sideFrac: Float = SIDE_DEFAULT,
        /** Gauge templates ([usesAa]): Android Auto (Google Maps / Waze) in the map card instead of OpenStreetMap. */
        val aaMap: Boolean = true,
    )

    /**
     * [l] connects through Android Auto (AASIDE, or any gauge template showing AA in its map card). Every
     * template but the grid has a map card; the small ones (Pro, supercar, Race Bar: about a third of the
     * dash) shrink AA, the rider still wanted it for real turn-by-turn on all of them.
     */
    fun usesAa(l: Layout): Boolean = l.aaMap && l.style.isPro

    /** Templates with the Android Auto / motorcycle (ZRide map) switch. */
    fun hasAaSwitch(s: Style): Boolean = s.isPro

    const val SIDE_MIN = 0.2f
    const val SIDE_MAX = 0.45f
    const val SIDE_DEFAULT = 0.3f
    /** What [Style.AASIDE]'s panel can show, top to bottom (the speed is always there). */
    val SIDE_PARTS = listOf(
        Part.FUEL, Part.TRIP, Part.ODO, Part.RIDE_TIME, Part.AVG, Part.MAX_SPEED, Part.RANGE, Part.ALTITUDE,
        Part.HEADING, Part.LEAN, Part.BIKE_BATTERY, Part.PHONE_TEMP, Part.TYRE, Part.MAINTENANCE, Part.GPS,
    )
    /** Premium templates drawn from the rider's mockups: everything shows on a fresh pick (no short side-panel list). */
    private val BIG_MAP = setOf(Style.NAV, Style.NAVC, Style.NAVT, Style.ADVNAV, Style.TOURNAV)

    private val OWN_PANEL_STYLES = setOf(Style.AASPORT.name, Style.AAGAUGE.name, Style.AARACE.name)
    /** Templates with an adjustable panel width ([Layout.sideFrac]). */
    val SIDE_STYLES = setOf(Style.AASIDE, Style.AARIGHT)
    /** A fresh [Style.AASIDE]: a short list that fits a narrow panel; the rider ticks more. */
    private val SIDE_HIDDEN = setOf(Part.AVG, Part.MAX_SPEED, Part.RANGE, Part.ALTITUDE, Part.HEADING, Part.LEAN, Part.TYRE, Part.MAINTENANCE)

    val DEFAULT = Layout(
        listOf(Widget.SPEED, Widget.CLOCK, Widget.TRIP, Widget.RIDE_TIME, Widget.HEADING, Widget.PHONE),
        ACCENTS[0],
    )

    /** Stored form → layout. Unknown names (from a newer/older app) are dropped, never crash. */
    internal fun parse(
        widgets: String?, accent: Int?, style: String? = null, tankLiters: Float? = null,
        accent2: Int? = null, customColor: Boolean = false, hidden: String? = null,
    ): Layout {
        val st = Style.entries.firstOrNull { it.name == style } ?: Style.GRID
        val tank = (tankLiters ?: 0f).coerceIn(0f, 60f)
        val list = widgets?.split(',')?.mapNotNull { n -> Widget.entries.firstOrNull { it.name == n.trim() } }
            ?.distinct()?.take(MAX_WIDGETS)
        return Layout(list?.ifEmpty { null } ?: DEFAULT.widgets, accent ?: DEFAULT.accent, st, tank, accent2, customColor, parseSet(hidden))
    }

    internal fun format(l: Layout): String = l.widgets.joinToString(",") { it.name }

    /** "A,B" → set of enum entries; unknown names (newer/older app) are dropped. */
    internal inline fun <reified E : Enum<E>> parseSet(s: String?): Set<E> =
        s?.split(',')?.mapNotNull { n -> enumValues<E>().firstOrNull { it.name == n.trim() } }?.toSet() ?: emptySet()

    internal fun formatSet(p: Set<Enum<*>>): String = p.sortedBy { it.ordinal }.joinToString(",") { it.name }

    /** The selected template with its own colours. */
    fun load(ctx: Context): Layout = load(ctx, null)

    /**
     * [style] (null = the selected one) with its own colours and hidden parts; widgets, tank and
     * the advanced options are shared. Colours used to be one set for every template: the old keys
     * now only seed the template that was selected back then.
     */
    fun load(ctx: Context, style: Style?): Layout {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString("style", null)
        // A paid template saved before a refund (or picked in a github build) falls back to the default.
        val st = style?.name ?: saved?.takeUnless { s -> Style.entries.firstOrNull { it.name == s }?.paid == true && !Premium.unlocked(ctx) }
        val k = "$st."
        val legacy = !p.contains(k + "accent") && st == saved
        fun int(key: String) = when {
            p.contains(k + key) -> p.getInt(k + key, 0)
            legacy && p.contains(key) -> p.getInt(key, 0)
            else -> null
        }
        // 0 saved = the rider never typed a size: use the picked Zontes model's tank.
        val tank = p.getFloat("tank", 0f).takeIf { it > 0f } ?: autoTank(ctx)
        return parse(
            p.getString("widgets", null), int("accent"), st, tank, int("accent2"),
            p.getBoolean(k + "custom", legacy && p.getBoolean("custom", false)),
            p.getString(k + "hidden", null) ?: if (st !in OWN_PANEL_STYLES && Style.entries.firstOrNull { it.name == st }?.category == Category.PREMIUM) formatSet(SIDE_HIDDEN) else null,
        ).copy(
            speedAlert = p.getInt("alert", 0), nightDim = p.getBoolean("night_dim", true),
            effects = p.getString("fx", null)?.let { parseSet<Fx>(it) } ?: Fx.entries.toSet(),
            navOnDash = p.getBoolean("nav_on_dash", true),
            sideFrac = p.getFloat("side_frac", SIDE_DEFAULT).coerceIn(SIDE_MIN, SIDE_MAX),
            aaMap = p.getBoolean("aa_on", true),  // Android Auto by default (big-screen templates)
        )
    }

    /** Most templates the ▲ handlebar key cycles through. */
    const val MAX_PAGES = 4

    /** Templates the ▲ handlebar key cycles through on the bike, in the order the rider ticked them. */
    fun pages(ctx: Context): List<Style> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pages", null)
            ?.split(',')?.mapNotNull { n -> Style.entries.firstOrNull { it.name == n } }?.distinct().orEmpty()

    fun setPage(ctx: Context, st: Style, on: Boolean) {
        val l = pages(ctx).filter { it != st } + if (on) listOf(st) else emptyList()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pages", l.takeLast(MAX_PAGES).joinToString(",") { it.name }).apply()
    }

    /** The template after [current] in [pages] (the first one if [current] isn't ticked); null = nothing to switch to. */
    internal fun next(pages: List<Style>, current: Style): Style? {
        if (pages.isEmpty() || pages == listOf(current)) return null
        return pages[(pages.indexOf(current) + 1) % pages.size]
    }

    /** Make [st] the selected template (its own colours and parts come with it). */
    fun select(ctx: Context, st: Style) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("style", st.name).apply()
    }

    /** Tank size of the Zontes model picked in the Garage (0 = unknown model). */
    fun autoTank(ctx: Context): Float = BikePicker.selected(ctx)?.tankL ?: 0f

    fun save(ctx: Context, l: Layout) {
        val k = "${l.style.name}."
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("widgets", format(l)).putString("style", l.style.name)
            .putFloat("tank", if (l.tankLiters == autoTank(ctx)) 0f else l.tankLiters)  // follows the bike model unless changed
            .putInt("alert", l.speedAlert).putBoolean("night_dim", l.nightDim).putString("fx", formatSet(l.effects))
            .putBoolean("nav_on_dash", l.navOnDash).putFloat("side_frac", l.sideFrac).putBoolean("aa_on", l.aaMap)
            .putInt(k + "accent", l.accent)
            .apply { if (l.accent2 != null) putInt(k + "accent2", l.accent2) else remove(k + "accent2") }
            .putBoolean(k + "custom", l.customColor).putString(k + "hidden", formatSet(l.hidden)).apply()
    }
}
