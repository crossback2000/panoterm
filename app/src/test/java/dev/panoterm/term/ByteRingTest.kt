package dev.panoterm.term

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ByteRingTest {
    @Test fun keepsEverythingUnderCapacity() {
        val r = ByteRing(10)
        r.write("abc".toByteArray()); r.write("def".toByteArray())
        assertArrayEquals("abcdef".toByteArray(), r.snapshot())
    }

    @Test fun dropsOldestWhenFull() {
        val r = ByteRing(8)
        r.write("012345".toByteArray())
        r.write("6789".toByteArray())
        assertArrayEquals("23456789".toByteArray(), r.snapshot())
        r.write("AB".toByteArray())
        assertArrayEquals("456789AB".toByteArray(), r.snapshot())
    }

    @Test fun hugeWriteKeepsTail() {
        val r = ByteRing(4)
        r.write("xy".toByteArray())
        r.write("0123456789".toByteArray())
        assertArrayEquals("6789".toByteArray(), r.snapshot())
    }
}
