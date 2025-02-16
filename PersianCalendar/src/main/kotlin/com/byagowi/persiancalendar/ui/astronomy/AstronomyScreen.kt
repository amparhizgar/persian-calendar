package com.byagowi.persiancalendar.ui.astronomy

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import androidx.core.view.isInvisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.byagowi.persiancalendar.R
import com.byagowi.persiancalendar.databinding.FragmentAstronomyBinding
import com.byagowi.persiancalendar.entities.Jdn
import com.byagowi.persiancalendar.entities.Season
import com.byagowi.persiancalendar.global.coordinates
import com.byagowi.persiancalendar.ui.calendar.dialogs.showDayPickerDialog
import com.byagowi.persiancalendar.ui.common.ArrowView
import com.byagowi.persiancalendar.ui.common.SolarDraw
import com.byagowi.persiancalendar.ui.utils.getCompatDrawable
import com.byagowi.persiancalendar.ui.utils.onClick
import com.byagowi.persiancalendar.ui.utils.setupMenuNavigation
import com.byagowi.persiancalendar.utils.formatDateAndTime
import com.byagowi.persiancalendar.utils.isRtl
import com.byagowi.persiancalendar.utils.toCivilDate
import com.byagowi.persiancalendar.utils.toJavaCalendar
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.cosinekitty.astronomy.SeasonsInfo
import io.github.cosinekitty.astronomy.seasons
import io.github.persiancalendar.calendar.CivilDate
import io.github.persiancalendar.calendar.PersianDate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs
import kotlin.math.min

class AstronomyScreen : Fragment(R.layout.fragment_astronomy) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentAstronomyBinding.bind(view)

        binding.appBar.toolbar.let {
            it.setTitle(R.string.astronomy)
            it.setupMenuNavigation()
        }

        val resetButton = binding.appBar.toolbar.menu.add(R.string.return_to_today).also {
            it.icon =
                binding.appBar.toolbar.context.getCompatDrawable(R.drawable.ic_restore_modified)
            it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            it.isVisible = false
        }

        val viewModel by viewModels<AstronomyViewModel>()
        if (viewModel.minutesOffset.value == AstronomyViewModel.DEFAULT_TIME)
            viewModel.animateToAbsoluteDayOffset(navArgs<AstronomyScreenArgs>().value.dayOffset)

        var clickCount = 0
        binding.solarView.onClick = { _, _ ->
            val activity = activity
            if (++clickCount % 2 == 0 && activity != null)
                showHoroscopesDialog(activity, viewModel.astronomyState.value.date.time)
        }

        val moonIconDrawable = object : Drawable() {
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit

            @Deprecated("", ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat"))
            override fun getOpacity(): Int = PixelFormat.OPAQUE

            private val solarDraw = SolarDraw(view.context)
            override fun draw(canvas: Canvas) {
                val radius = min(bounds.width(), bounds.height()) / 2f
                val sun = viewModel.astronomyState.value.sun
                val moon = viewModel.astronomyState.value.moon
                solarDraw.moon(canvas, sun, moon, radius, radius, radius)
            }
        }
        binding.railView.itemIconTintList = null // makes it to not apply tint on modes icons
        binding.railView.menu.also { menu ->
            val buttons = enumValues<AstronomyMode>()
                .associateWith { menu.add(it.title).setIcon(it.icon) }
            binding.railView.post { // Needs to be done in .post so selected button is applied correctly
                buttons.forEach { (mode, item) ->
                    if (viewModel.mode.value == mode) item.isChecked = true
                    item.onClick { viewModel.changeScreenMode(mode) }
                }
            }
            // Special case for moon icon
            buttons[AstronomyMode.Moon]?.icon = moonIconDrawable
        }

        val seasonsCache = hashMapOf<Int, SeasonsInfo>()
        fun calculateSeasons(year: Int) = seasonsCache.getOrPut(year) { seasons(year) }
        val headerCache = hashMapOf<Long, String>()

        fun update(state: AstronomyState) {
            binding.solarView.setTime(state)
            moonIconDrawable.invalidateSelf() // Update moon icon of rail view

            val tropical = viewModel.isTropical.value
            val sunZodiac =
                if (tropical) Zodiac.fromTropical(state.sun.elon)
                else Zodiac.fromIau(state.sun.elon)
            val moonZodiac =
                if (tropical) Zodiac.fromTropical(state.moon.elon)
                else Zodiac.fromIau(state.moon.elon)

            binding.sunText.text = sunZodiac.format(view.context, true) // ☉☀️
            binding.moonText.text =
                moonZodiac.format(binding.root.context, true) // ☽it.moonPhaseEmoji
            binding.time.text = state.date.formatDateAndTime()

            val civilDate = state.date.toCivilDate()
            val jdn = civilDate.toJdn()
            val persianDate = PersianDate(jdn)
            binding.headerInformation.text = headerCache.getOrPut(jdn) {
                state.generateHeader(context ?: return, persianDate)
            }

            (1..4).forEach { i ->
                when (i) {
                    1 -> binding.firstSeasonText
                    2 -> binding.secondSeasonText
                    3 -> binding.thirdSeasonText
                    else -> binding.fourthSeasonText
                }.text = Date(
                    calculateSeasons(
                        CivilDate(PersianDate(persianDate.year, i * 3, 29)).year
                    ).let {
                        when (i) {
                            1 -> it.juneSolstice
                            2 -> it.septemberEquinox
                            3 -> it.decemberSolstice
                            else -> it.marchEquinox
                        }
                    }.toMillisecondsSince1970()
                ).toJavaCalendar().formatDateAndTime()
            }
        }

        val tropicalMenuItem = binding.appBar.toolbar.menu.add(R.string.tropical).also { menuItem ->
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menuItem.actionView = SwitchMaterial(binding.appBar.toolbar.context).also { switch ->
                @SuppressLint("SetTextI18n")
                switch.text = getString(R.string.tropical) + " "
                switch.isChecked = viewModel.isTropical.value
                switch.setOnClickListener { viewModel.changeTropicalStatus(switch.isChecked) }
            }
        }

        listOf(
            binding.firstSeasonChip to 4, binding.secondSeasonChip to 7,
            binding.thirdSeasonChip to 10, binding.fourthSeasonChip to 1
        ).map { (chip, month) -> // 'month' is month number of first Persian month in the season
            val season = Season.fromPersianCalendar(PersianDate(1400, month, 1), coordinates)
            chip.setText(season.nameStringId)
            chip.chipBackgroundColor = ColorStateList.valueOf(season.color)
        }

        binding.appBar.toolbar.menu.add(R.string.goto_date).also {
            it.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            it.onClick {
                val startJdn = Jdn(viewModel.astronomyState.value.date.toCivilDate())
                showDayPickerDialog(activity ?: return@onClick, startJdn, R.string.go) { jdn ->
                    viewModel.animateToAbsoluteDayOffset(jdn - Jdn.today())
                }
            }
        }

        resetButton.onClick {
            viewModel.animateToAbsoluteMinutesOffset(0)
            resetButton.isVisible = false
        }

        val viewDirection = if (resources.isRtl) -1 else 1

        var lastButtonClickTimestamp = System.currentTimeMillis()
        binding.slider.smoothScrollBy(200 * viewDirection, 0)

        var latestVibration = 0L
        binding.slider.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx == 0) return
                val current = System.currentTimeMillis()
                if (current - lastButtonClickTimestamp < 2000) return
                if (current >= latestVibration + 35_000 / abs(dx)) {
                    binding.slider.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    latestVibration = current
                }
                viewModel.addMinutesOffset(dx * viewDirection)
            }
        })

        fun buttonScrollSlider(days: Int): Boolean {
            lastButtonClickTimestamp = System.currentTimeMillis()
            binding.slider.smoothScrollBy(50 * days * viewDirection, 0)
            viewModel.animateToRelativeDayOffset(days)
            return true
        }
        binding.startArrow.rotateTo(ArrowView.Direction.START)
        binding.startArrow.setOnClickListener {
            binding.startArrow.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            buttonScrollSlider(-1)
        }
        binding.startArrow.setOnLongClickListener { buttonScrollSlider(-365) }
        binding.startArrow.contentDescription =
            getString(R.string.previous_x, getString(R.string.day))
        binding.endArrow.rotateTo(ArrowView.Direction.END)
        binding.endArrow.setOnClickListener {
            binding.endArrow.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            buttonScrollSlider(1)
        }
        binding.endArrow.setOnLongClickListener { buttonScrollSlider(365) }
        binding.endArrow.contentDescription = getString(R.string.next_x, getString(R.string.day))

        val layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.APPEARING or LayoutTransition.DISAPPEARING)
            setAnimateParentHierarchy(false)
        }
        binding.firstColumn.layoutTransition = layoutTransition
        binding.secondColumn.layoutTransition = layoutTransition

        // Setup view model change listeners
        // https://developer.android.com/topic/libraries/architecture/coroutines#lifecycle-aware
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isTropical.collectLatest {
                        update(viewModel.astronomyState.value)
                        binding.solarView.isTropicalDegree = it
                    }
                }
                launch {
                    viewModel.resetButtonVisibilityEvent.collectLatest {
                        resetButton.isVisible = it
                    }
                }
                launch {
                    viewModel.mode.collectLatest {
                        binding.solarView.mode = it
                        val showTropicalRelatedElements = it == AstronomyMode.Earth
                        tropicalMenuItem.isVisible = showTropicalRelatedElements
                        binding.sunStatusWrapper.isInvisible = !showTropicalRelatedElements
                        binding.moonStatusWrapper.isInvisible = !showTropicalRelatedElements
                    }
                }
                launch { viewModel.astronomyState.collectLatest(::update) }
            }
        }
    }
}
