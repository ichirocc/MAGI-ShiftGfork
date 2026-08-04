package com.magi.app.v6.engine

import com.magi.app.v6.ViolationReport

/**
 * main: weightedScore は Double
 * 参照 domain: Long の場合もあるため Number 化して扱う
 */
fun ViolationReport.weightedLong(): Long = weightedScore.toLong()
