package com.limelight.utils

import android.net.TrafficStats

object TrafficStatsHelper {
    @JvmStatic
    fun getAllRxBytes(): Long {
        return TrafficStats.getTotalRxBytes()
    }

    @JvmStatic
    fun getAllTxBytes(): Long {
        return TrafficStats.getTotalTxBytes()
    }

    @JvmStatic
    fun getAllRxBytesMobile(): Long {
        return TrafficStats.getMobileRxBytes()
    }

    @JvmStatic
    fun getAllTxBytesMobile(): Long {
        return TrafficStats.getMobileTxBytes()
    }

    @JvmStatic
    fun getAllRxBytesWifi(): Long {
        return TrafficStats.getTotalRxBytes() - TrafficStats.getMobileRxBytes()
    }

    @JvmStatic
    fun getAllTxBytesWifi(): Long {
        return TrafficStats.getTotalTxBytes() - TrafficStats.getMobileTxBytes()
    }

    @JvmStatic
    fun getPackageRxBytes(uid: Int): Long {
        return TrafficStats.getUidRxBytes(uid)
    }

    @JvmStatic
    fun getPackageTxBytes(uid: Int): Long {
        return TrafficStats.getUidTxBytes(uid)
    }
}
