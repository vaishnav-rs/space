package com.perfectframe.camera.composition

import com.perfectframe.camera.vision.DetectedSubject
import com.perfectframe.camera.vision.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the composition scorer — the one piece of the pipeline verifiable without
 * a device. Covers the v1 contract: dominant-subject selection, thirds placement, lead room,
 * edge-clip / tilt penalties, and the show/ideal gates.
 */
class CompositionEngineTest {

    private val engine = CompositionEngine()
    private val tol = 1.5f

    private fun face(
        cx: Float, cy: Float, w: Float, h: Float,
        yaw: Float? = null, confidence: Float = 1f,
    ): DetectedSubject = DetectedSubject(
        kind = SubjectKind.FACE,
        box = NormRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
        confidence = confidence,
        facingYawDegrees = yaw,
    )

    @Test
    fun noSubjects_producesNoConfidentFrame() {
        val frame = engine.compute(emptyList(), horizonRollDegrees = 0f, levelToleranceDegrees = tol)
        assertFalse(frame.hasSubject)
        assertEquals(0f, frame.confidence, 0.0001f)
        assertFalse(engine.shouldShow(frame))
    }

    @Test
    fun cropStaysInsideUnitSquare_evenForEdgeAnchor() {
        val frame = engine.compute(listOf(face(cx = 0.95f, cy = 0.95f, w = 0.2f, h = 0.25f)), 0f, tol)
        assertTrue(frame.box.left >= 0f)
        assertTrue(frame.box.top >= 0f)
        assertTrue(frame.box.right <= 1f)
        assertTrue(frame.box.bottom <= 1f)
    }

    @Test
    fun wellPlacedLevelFace_isIdealAndShown() {
        // Face sized/positioned so the thirds crop needs no clamping and the horizon is level.
        val frame = engine.compute(
            listOf(face(cx = 0.42f, cy = 0.38f, w = 0.17f, h = 0.20f)),
            horizonRollDegrees = 0f,
            levelToleranceDegrees = tol,
        )
        assertTrue("expected confident frame, got ${frame.confidence}", frame.confidence >= 0.74f)
        assertTrue(frame.isIdeal)
        assertTrue(engine.shouldShow(frame))
    }

    @Test
    fun rightFacingSubject_getsLeadRoomOnTheRight() {
        val f = face(cx = 0.45f, cy = 0.4f, w = 0.16f, h = 0.2f, yaw = 25f)
        val frame = engine.compute(listOf(f), 0f, tol)
        // Subject sits on the left third → crop center lands to the RIGHT of the face.
        assertTrue(frame.box.centerX > f.box.centerX)
    }

    @Test
    fun leftFacingSubject_getsLeadRoomOnTheLeft() {
        val f = face(cx = 0.55f, cy = 0.4f, w = 0.16f, h = 0.2f, yaw = -25f)
        val frame = engine.compute(listOf(f), 0f, tol)
        assertTrue(frame.box.centerX < f.box.centerX)
    }

    @Test
    fun tiltedHorizon_isNotIdealAndAsksToLevel() {
        val frame = engine.compute(
            listOf(face(cx = 0.42f, cy = 0.38f, w = 0.17f, h = 0.2f)),
            horizonRollDegrees = 10f,
            levelToleranceDegrees = tol,
        )
        assertFalse(frame.isIdeal)
        assertTrue(Nudge.LEVEL_HORIZON in frame.nudges)
    }

    @Test
    fun edgeClippedSubject_isNotIdeal() {
        // Face pushed hard against the right edge.
        val frame = engine.compute(
            listOf(face(cx = 0.97f, cy = 0.4f, w = 0.18f, h = 0.22f)),
            horizonRollDegrees = 0f,
            levelToleranceDegrees = tol,
        )
        assertFalse(frame.isIdeal)
    }

    @Test
    fun faceWeightingTipsComparablySizedObject() {
        // Face is slightly smaller by area, but the FACE_WEIGHT (2.2x) makes it the dominant
        // subject. Object area 0.036*0.9 = 0.032 < face area 0.018*2.2 = 0.040 prominence.
        val smallFace = face(cx = 0.4f, cy = 0.4f, w = 0.12f, h = 0.15f)
        val comparableObject = DetectedSubject(
            kind = SubjectKind.OBJECT,
            box = NormRect(0.10f, 0.10f, 0.28f, 0.30f), // 0.18 x 0.20
            confidence = 0.9f,
        )
        val frame = engine.compute(listOf(comparableObject, smallFace), 0f, tol)
        assertTrue(frame.hasSubject)
        // Face drives an upper-third crop; a face-anchored crop keeps the top well below 0.5.
        assertTrue("crop should be face-driven (upper third)", frame.box.top < 0.45f)
        assertTrue(frame.box.height < 0.95f) // a real crop, not the whole frame
    }
}
