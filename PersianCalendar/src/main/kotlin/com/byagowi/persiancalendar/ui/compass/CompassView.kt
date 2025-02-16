// copyedited from https://code.google.com/p/android-salat-times/source/browse/src/com/cepmuvakkit/times/view/QiblaCompassView.java
// licensed under GPLv3
package com.byagowi.persiancalendar.ui.compass

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import com.byagowi.persiancalendar.R
import com.byagowi.persiancalendar.entities.Clock
import com.byagowi.persiancalendar.global.coordinates
import com.byagowi.persiancalendar.ui.common.AngleDisplay
import com.byagowi.persiancalendar.ui.common.SolarDraw
import com.byagowi.persiancalendar.ui.utils.dp
import com.byagowi.persiancalendar.ui.utils.resolveColor
import com.byagowi.persiancalendar.ui.utils.sp
import com.byagowi.persiancalendar.utils.EarthPosition
import com.byagowi.persiancalendar.utils.toObserver
import java.util.*
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

class CompassView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var angle = 0f
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    private val northwardShapePath = Path()
    private val northArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.RED
        it.style = Paint.Style.FILL
        it.alpha = 100
    }
    private val markerPaint = Paint(Paint.FAKE_BOLD_TEXT_FLAG).also {
        it.color = ContextCompat.getColor(context, R.color.qibla_color)
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = ContextCompat.getColor(context, R.color.qibla_color)
        it.strokeWidth = 0.5.dp
        it.style = Paint.Style.STROKE // Sadece Cember ciziyor.
    }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.color = Color.LTGRAY
        it.strokeWidth = 1.dp
    }
    private val moonShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0x808080FF.toInt()
        it.style = Paint.Style.STROKE
        it.strokeWidth = 9.dp
        it.strokeCap = Paint.Cap.ROUND
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeWidth = 1.dp
    }
    private val sunShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0x80808080.toInt()
        it.style = Paint.Style.STROKE
        it.strokeWidth = 9.dp
        it.strokeCap = Paint.Cap.ROUND
    }
    private val qiblaPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xFF009000.toInt()
        it.style = Paint.Style.FILL_AND_STROKE
        it.strokeWidth = 1.dp
        it.pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val kaaba = BitmapFactory.decodeResource(resources, R.drawable.kaaba)

    private var cx = 0f
    private var cy = 0f // Center of Compass (cx, cy)
    private var radius = 0f // radius of Compass dial
    private var r = 0f // radius of Sun and Moon

    private val observer = coordinates?.toObserver()
    private var astronomyState = observer?.let { AstronomyState(it, GregorianCalendar()) }

    private val fullDay = Clock(24, 0).toMinutes().toFloat()
    private var sunProgress = Clock(Calendar.getInstance()).toMinutes() / fullDay

    private val enableShade = false

    fun setTime(time: GregorianCalendar) {
        astronomyState = observer?.let { AstronomyState(it, time) }
        sunProgress = Clock(time).toMinutes() / fullDay
        invalidate()
    }

    val qiblaHeading = coordinates?.run {
        EarthPosition(latitude, longitude).toEarthHeading(EarthPosition(21.422522, 39.826181))
    }
    var isShowQibla = true
        set(value) {
            field = value
            invalidate()
        }
    private val planetsPaint = Paint(Paint.FAKE_BOLD_TEXT_FLAG).also {
        it.color = ContextCompat.getColor(context, R.color.qibla_color)
        it.textSize = 12.sp
        it.textAlign = Paint.Align.CENTER
    }
    private val textPaint = Paint(Paint.FAKE_BOLD_TEXT_FLAG).also {
        it.color = ContextCompat.getColor(context, R.color.qibla_color)
        it.textSize = 12.sp
        it.textAlign = Paint.Align.CENTER
    }
    private val textStrokePaint = Paint(Paint.FAKE_BOLD_TEXT_FLAG).also {
        it.color = context.resolveColor(R.attr.colorCard)
        it.strokeWidth = 5.dp
        it.style = Paint.Style.STROKE
        it.textSize = 12.sp
        it.textAlign = Paint.Align.CENTER
    }

    private val angleDisplay = AngleDisplay(context, "0", "888")

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        angleDisplay.updatePlacement(w / 2, h)

        cx = w / 2f
        cy = h / 2f - angleDisplay.lcdHeight
        radius = min(cx - cx / 12, cy - cy / 12)
        r = radius / 10 // Sun Moon radius

        // Construct a wedge-shaped path
        northwardShapePath.also {
            val r = radius / 12
            it.rewind()
            it.moveTo(cx, cy - radius)
            it.lineTo(cx - r, cy)
            it.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, -180f)
            it.close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        angleDisplay.draw(canvas, (round(angle) + 360f) % 360f)
        canvas.withRotation(-angle, cx, cy) {
            drawDial()
            drawPath(northwardShapePath, northArrowPaint)
            if (coordinates != null) {
                drawQibla()
                drawPlanets()
                drawMoon()
                drawSun()
            }
        }
    }

    private fun Canvas.drawDial() {
        // Draw the background
        drawCircle(cx, cy, radius, circlePaint)
        drawCircle(cx, cy, radius * .975f, circlePaint)
        // Rotate our perspective so that the "top" is
        // facing the current bearing.
        val cardinalX = cx
        val cardinalY = cy - radius * .85f

        // Draw the marker every 15 degrees and text every 45.
        (0..23).forEach {
            withRotation(15f * it, cx, cy) {
                drawLine(cx, cy - radius, cx, cy - radius * .975f, markerPaint)
                // Draw the cardinal points
                if (it % 6 == 0) {
                    val dirString = when (it) {
                        0 -> "N"
                        6 -> "E"
                        12 -> "S"
                        18 -> "W"
                        else -> ""
                    }
                    drawText(dirString, cardinalX, cardinalY, textPaint)
                } else if (it % 3 == 0) {
                    // Draw the text every alternate 45deg
                    drawText((it * 15).toString(), cardinalX, cardinalY, textPaint)
                }
            }
        }
    }

    private val solarDraw = SolarDraw(context)

    private val shadeFactor = 1.5f

    private fun Canvas.drawSun() {
        val astronomyState = astronomyState ?: return
        if (astronomyState.isNight) return
        val rotation = astronomyState.sunHorizon.azimuth.toFloat()
        withRotation(rotation, cx, cy) {
            val sunHeight = (astronomyState.sunHorizon.altitude.toFloat() / 90 - 1) * radius
            val sunColor = solarDraw.sunColor(sunProgress)
            sunPaint.color = sunColor
            drawLine(cx, cy - radius, cx, cy + radius, sunPaint)
            if (enableShade) drawLine(cx, cy, cx, cy - sunHeight / shadeFactor, sunShadePaint)
            solarDraw.sun(this, cx, cy + sunHeight, r, sunColor)
        }
    }

    private fun Canvas.drawMoon() {
        val astronomyState = astronomyState ?: return
        if (astronomyState.isMoonGone) return
        val azimuth = astronomyState.moonHorizon.azimuth.toFloat()
        withRotation(azimuth, cx, cy) {
            val moonHeight = (astronomyState.moonHorizon.altitude.toFloat() / 90 - 1) * radius
            drawLine(cx, cy - radius, cx, cy + radius, moonPaint)
            if (enableShade) drawLine(cx, cy, cx, cy - moonHeight / shadeFactor, moonShadePaint)
            withRotation(-azimuth + angle, cx, cy + moonHeight) {
                solarDraw.moon(
                    this, astronomyState.sun, astronomyState.moon, cx, cy + moonHeight,
                    r * .8f, astronomyState.moonTiltAngle
                )
            }
        }
    }

    private fun Canvas.drawPlanets() {
        val astronomyState = astronomyState ?: return
        planetsPaint.alpha = (127 - astronomyState.sunHorizon.altitude.toInt() * 3).coerceIn(0, 255)
        astronomyState.planets.forEach { (title, planetHorizon) ->
            val azimuth = planetHorizon.azimuth.toFloat()
            val planetHeight = (planetHorizon.altitude.toFloat() / 90 - 1) * radius
            withRotation(azimuth, cx, cy) {
                drawCircle(cx, cy + planetHeight, radius / 120, planetsPaint)
                withRotation(-azimuth + angle, cx, cy + planetHeight) {
                    drawText(
                        resources.getString(title), cx, cy + planetHeight - radius / 40,
                        planetsPaint
                    )
                }
            }
        }
    }

    private fun Canvas.drawQibla() {
        if (!isShowQibla) return
        val qiblaHeading = qiblaHeading ?: return
        withRotation(qiblaHeading.heading.toFloat(), cx, cy) {
            drawLine(cx, cy - radius, cx, cy + radius, qiblaPaint)
            drawBitmap(kaaba, cx - kaaba.width / 2, cy - radius - kaaba.height / 2, null)
            val textCenter = cy - radius / 2
            withRotation(90f, cx, textCenter) {
                val distance =
                    "%,d km".format(Locale.ENGLISH, (qiblaHeading.metres / 1000f).roundToInt())
                drawText(distance, cx, textCenter + 4.dp, textStrokePaint)
                drawText(distance, cx, textCenter + 4.dp, textPaint)
            }
        }
    }
}
