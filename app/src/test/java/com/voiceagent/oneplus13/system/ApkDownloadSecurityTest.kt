package com.voiceagent.oneplus13.system

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloadSecurityTest {
    @Test fun blocksLocalAndReservedAddresses() {
        listOf("127.0.0.1", "10.0.0.4", "192.168.1.2", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "fe80::1").forEach {
            assertFalse(it, isPublicDownloadAddress(InetAddress.getByName(it)))
        }
        assertTrue(isPublicDownloadAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicDownloadAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
