package com.synera.api.organization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsDistanceTest {
    @Test
    void identicalCoordinatesHaveZeroDistance() {
        assertEquals(0, GpsDistance.meters(10.75, 106.66, 10.75, 106.66));
    }

    @Test
    void measuresDistanceBetweenNearbyCoordinates() {
        assertEquals(111, GpsDistance.meters(0, 0, 0.001, 0));
    }

    @Test
    void antipodalCoordinatesProduceFiniteDistance() {
        assertTrue(GpsDistance.meters(0, 0, 0, 180) > 20_000_000);
    }
}
