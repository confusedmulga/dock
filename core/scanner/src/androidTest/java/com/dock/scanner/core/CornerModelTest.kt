package com.dock.scanner.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class CornerModelTest {

    companion object {
        private lateinit var model: CornerModel

        @BeforeClass
        @JvmStatic
        fun load() {
            model = CornerModel.load(InstrumentationRegistry.getInstrumentation().targetContext)
        }

        @AfterClass
        @JvmStatic
        fun close() = model.close()
    }

    /**
     * A white page of printed text, photographed at an angle ([corners]) on a desk of [desk]
     * gray, darker towards the left (a hand's shadow).
     */
    private fun photo(desk: Double, corners: List<Point>, w: Int = 1200, h: Int = 1600): Mat {
        val page = Mat(1100, 850, CvType.CV_8UC1, Scalar(250.0))
        for (line in 0 until 30) {
            Imgproc.putText(page, "The quick brown fox jumps over the lazy dog 1234", Point(60.0, 90.0 + line * 32), Imgproc.FONT_HERSHEY_SIMPLEX, 0.75, Scalar(30.0), 2)
        }
        val transform = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(Point(0.0, 0.0), Point(850.0, 0.0), Point(850.0, 1100.0), Point(0.0, 1100.0)),
            MatOfPoint2f(*corners.toTypedArray()),
        )
        val img = Mat(h, w, CvType.CV_8UC1, Scalar(desk))
        Imgproc.warpPerspective(page, img, transform, org.opencv.core.Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_TRANSPARENT)
        val shade = Mat(h, w, CvType.CV_32F)
        for (x in 0 until w) shade.col(x).setTo(Scalar(0.75 + 0.25 * x / w))
        img.convertTo(img, CvType.CV_32F)
        Core.multiply(img, shade, img)
        img.convertTo(img, CvType.CV_8U)
        listOf(page, transform, shade).forEach(Mat::release)
        return img
    }

    private fun assertNear(expected: List<Point>, found: Quad?, w: Int = 1200, h: Int = 1600) {
        assertNotNull("no page found", found)
        expected.zip(found!!.corners).forEach { (e, f) ->
            val dx = e.x / w - f.x
            val dy = e.y / h - f.y
            assertTrue("corner off by ${dx * 100}%, ${dy * 100}%", kotlin.math.abs(dx) < 0.03 && kotlin.math.abs(dy) < 0.03)
        }
    }

    @Test
    fun findsAWhitePageOnAWhiteDesk() {
        val corners = listOf(Point(260.0, 120.0), Point(1000.0, 260.0), Point(920.0, 1500.0), Point(90.0, 1330.0))
        val img = photo(desk = 230.0, corners)
        assertNear(corners, model.detect(img))
        img.release()
    }

    @Test
    fun answersForTheUprightImage() {
        val corners = listOf(Point(150.0, 150.0), Point(1050.0, 200.0), Point(1000.0, 1450.0), Point(200.0, 1400.0))
        val img = photo(desk = 120.0, corners)
        // The same photo as a sensor would deliver it: turned a quarter to the left.
        val sideways = Mat()
        Core.rotate(img, sideways, Core.ROTATE_90_COUNTERCLOCKWISE)
        assertNear(corners, model.detect(sideways, rotationDegrees = 90))
        img.release()
        sideways.release()
    }

    private fun asset(name: String): Mat {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
        return Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_GRAYSCALE)
    }

    @Test
    fun overrulesTheClassicDetectorWhenItIsFooled() {
        // A page on a beige desk: the classic detector pulls the right-hand corners to the frame's edge.
        val img = asset("light_desk.jpg")
        val classic = DocumentDetector().detect(img)
        val picked = CornerModel.pick(classic, model.detect(img))
        val truth = listOf(Point(260.0, 120.0), Point(1000.0, 260.0), Point(920.0, 1500.0), Point(90.0, 1330.0))
        assertNear(truth, picked)
        assertTrue("the classic outline should be off here", classic == null || classic.maxCornerDistance(picked!!) > 0.05f)
        img.release()
    }

    @Test
    fun keepsTheClassicOutlineWhenTheyAgree() {
        val img = asset("id_card.jpg")
        val classic = DocumentDetector().detect(img)
        assertNotNull(classic)
        assertTrue(CornerModel.pick(classic, model.detect(img)) === classic)
        img.release()
    }

    @Test
    fun findsNothingWithoutAPage() {
        val img = Mat(1600, 1200, CvType.CV_8UC1)
        Core.randu(img, 60.0, 200.0)
        Imgproc.GaussianBlur(img, img, org.opencv.core.Size(0.0, 0.0), 6.0)
        assertNull(model.detect(img))
        img.release()
    }
}
