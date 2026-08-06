package com.magi.app.v6

/**
 * ## Kotlin / C++ 棲み分け（要約）
 *
 * 詳細: `docs/engine-rebuild/KOTLIN_CPP_BOUNDARY.md`
 *
 * - **Kotlin**: Session・Move 契約・betterReport・Checker（場所/説明）・パイプライン・UI
 * - **C++**: nativeFullEval / deltaApply / SA・LAHC・ALNS・Polish チャンク（数値のみ）
 * - **境界**: [NativeBridge], [NativeFullEval], [NativeParityGate], [NativeGate]
 * - **衝突時**: パリティ不一致なら C++ を閉じ、Kotlin のみで継続（落とさない）
 */
object KotlinCppBoundary {
    const val DOC = "fork-overlay/docs/engine-rebuild/KOTLIN_CPP_BOUNDARY.md"
}
