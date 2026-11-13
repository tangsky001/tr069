package com.tr069.acs.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentDeliveryVerifyValueTest {

    @Test
    void booleanSpellingsAreEqual() {
        assertTrue(IntentDeliveryService.sameValue("1", "true"));
        assertTrue(IntentDeliveryService.sameValue("是", "true"));
        assertTrue(IntentDeliveryService.sameValue("false", "0"));
    }

    @Test
    void numbersComparedByValue() {
        assertTrue(IntentDeliveryService.sameValue("1000", "1000.0"));
        assertTrue(IntentDeliveryService.sameValue("1500", " 1500 "));
        assertFalse(IntentDeliveryService.sameValue("1500", "1492"));
    }

    @Test
    void textIgnoringCase() {
        assertTrue(IntentDeliveryService.sameValue("AESEncryption", "aesencryption"));
        assertFalse(IntentDeliveryService.sameValue("PPPoE", "PPPoE_Bridged"));
    }

    @Test
    void nullIsNeverEqual() {
        assertFalse(IntentDeliveryService.sameValue(null, "x"));
        assertFalse(IntentDeliveryService.sameValue("x", null));
    }
}
