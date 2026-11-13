package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Icons taken from Opaline's icon set (Apache-2.0, a derivative of Miuix),
 * path data unchanged, plus the two hearts the player's like button draws.
 *
 * Tint is applied by [androidx.compose.material3.Icon]; the lighter parts of
 * the two-tone device glyphs are the same ink at a lower alpha, so they follow
 * whatever tint they are given.
 */
object OpalineIcons {

    private val ink = SolidColor(Color.Black)

    /** Sound bars in a filled disc: Settings' Direct Source Stream. */
    val Recording: ImageVector by lazy {
        ImageVector.Builder(
            name = "opaline_recording", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 1232.4f, viewportHeight = 1232.4f,
        ).apply {
            // Drawn y-up, as the font it was cut from is.
            group(scaleY = -1f, translationY = 1232.4f) {
                addPath(
                    pathData = addPathNodes("M1129.7 616.2 Q1129.7 755.2 1060.2 872.7 Q990.7 990.2 873.2 1059.7 Q755.7 1129.2 616.7 1129.2 Q477.7 1129.2 359.7 1059.7 Q241.7 990.2 172.2 872.7 Q102.7 755.2 102.7 616.2 Q102.7 477.2 172.2 359.7 Q241.7 242.2 359.7 172.7 Q477.7 103.2 616.7 103.2 Q755.7 103.2 873.2 172.7 Q990.7 242.2 1060.2 359.7 Q1129.7 477.2 1129.7 616.2 Z M287.7 460.2 V757.2 Q287.7 771.2 297.7 781.2 Q307.7 791.2 321.7 791.2 H337.7 Q351.7 791.2 361.7 781.2 Q371.7 771.2 371.7 757.2 V460.2 Q371.7 447.2 361.2 437.2 Q350.7 427.2 337.7 427.2 H321.7 Q307.7 427.2 297.7 437.2 Q287.7 447.2 287.7 460.2 Z M430.7 500.2 V717.2 Q430.7 731.2 441.2 741.2 Q451.7 751.2 465.7 751.2 H481.7 Q495.7 751.2 505.7 741.2 Q515.7 731.2 515.7 717.2 V500.2 Q515.7 487.2 505.2 477.2 Q494.7 467.2 481.7 467.2 H465.7 Q451.7 467.2 441.2 477.2 Q430.7 487.2 430.7 500.2 Z M716.7 444.2 V773.2 Q716.7 787.2 726.7 797.2 Q736.7 807.2 751.7 807.2 H767.7 Q781.7 807.2 791.7 797.2 Q801.7 787.2 801.7 773.2 V444.2 Q801.7 431.2 791.2 421.2 Q780.7 411.2 767.7 411.2 H751.7 Q736.7 411.2 726.7 421.2 Q716.7 431.2 716.7 444.2 Z M573.7 349.2 V883.2 Q573.7 897.2 583.7 907.2 Q593.7 917.2 607.7 917.2 H623.7 Q637.7 917.2 647.7 907.2 Q657.7 897.2 657.7 883.2 V349.2 Q657.7 336.2 647.2 326.2 Q636.7 316.2 623.7 316.2 H607.7 Q593.7 316.2 583.7 326.2 Q573.7 336.2 573.7 349.2 Z M860.7 544.2 V673.2 Q860.7 687.2 870.7 697.2 Q880.7 707.2 894.7 707.2 H910.7 Q924.7 707.2 934.7 697.2 Q944.7 687.2 944.7 673.2 V544.2 Q944.7 531.2 934.2 521.2 Q923.7 511.2 910.7 511.2 H894.7 Q880.7 511.2 870.7 521.2 Q860.7 531.2 860.7 544.2 Z"),
                    fill = ink,
                )
            }
        }.build()
    }

    /** Three dots in a ring: the player's menu. */
    val MoreCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "opaline_more_circle", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 1258.8f, viewportHeight = 1258.8f,
        ).apply {
            // Drawn y-up, as the font it was cut from is.
            group(scaleY = -1f, translationY = 1258.8f) {
                addPath(
                    pathData = addPathNodes("M1153.9 628.9 Q1153.9 770.9 1083.4 891.4 Q1012.8 1011.8 892.4 1082.9 Q771.9 1153.9 629.9 1153.9 Q487.9 1153.9 367.4 1082.9 Q247.0 1011.8 175.9 891.4 Q104.9 770.9 104.9 628.9 Q104.9 486.9 175.9 366.4 Q247.0 246.0 367.4 175.4 Q487.9 104.9 629.9 104.9 Q771.9 104.9 892.4 175.4 Q1012.8 246.0 1083.4 366.4 Q1153.9 486.9 1153.9 628.9 Z M218.9 628.8 Q218.9 740.9 274.0 835.2 Q329.1 929.6 423.4 984.7 Q517.7 1039.9 629.9 1039.9 Q741.9 1039.9 835.9 984.8 Q930.0 929.7 985.4 835.4 Q1040.9 741.2 1040.9 629.0 Q1040.9 516.9 985.4 422.6 Q930.0 328.2 835.9 273.1 Q741.9 217.9 630.4 217.9 Q517.9 217.9 423.6 273.0 Q329.2 328.1 274.1 422.4 Q218.9 516.6 218.9 628.8 Z M487.9 628.9 Q487.9 657.9 467.4 677.9 Q446.9 697.9 419.5 697.9 Q392.0 697.9 371.5 677.4 Q350.9 656.9 350.9 628.8 Q350.9 601.9 371.0 581.4 Q391.0 560.9 419.9 560.9 Q447.9 560.9 467.9 581.1 Q487.9 601.2 487.9 628.9 Z M697.9 628.9 Q697.9 657.9 677.4 677.9 Q656.9 697.9 629.5 697.9 Q602.0 697.9 581.5 677.4 Q560.9 656.9 560.9 628.8 Q560.9 601.9 581.0 581.4 Q601.0 560.9 629.9 560.9 Q657.9 560.9 677.9 581.1 Q697.9 601.2 697.9 628.9 Z M907.9 628.9 Q907.9 657.9 887.4 677.9 Q866.9 697.9 839.5 697.9 Q810.8 697.9 790.8 677.4 Q770.9 656.9 770.9 628.9 Q770.9 600.9 791.0 580.9 Q811.0 560.9 839.9 560.9 Q867.9 560.9 887.9 581.1 Q907.9 601.2 907.9 628.9 Z"),
                    fill = ink,
                )
            }
        }.build()
    }

    /** A radar sweep: Listen Together, in the output sheet. */
    val SearchDevice: ImageVector by lazy {
        ImageVector.Builder(
            name = "opaline_search_device", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 1300.8f, viewportHeight = 1300.8f,
        ).apply {
            // Drawn y-up, as the font it was cut from is.
            group(scaleY = -1f, translationY = 1300.8f) {
                addPath(
                    pathData = addPathNodes("M1036.9 289.4 Q1110.9 367.4 1147.9 467.4 Q1184.9 567.4 1181.9 668.4 Q1178.9 769.4 1137.9 851.4 Q1129.9 869.4 1114.9 872.9 Q1099.9 876.4 1087.9 864.4 L1055.9 836.4 Q1041.9 824.4 1040.4 814.4 Q1038.9 804.4 1042.9 790.4 Q1069.9 726.4 1069.9 651.4 Q1069.9 576.4 1041.4 501.9 Q1012.9 427.4 954.9 367.4 Q877.9 286.4 772.4 256.4 Q666.9 226.4 560.9 251.4 Q454.9 276.4 373.9 353.4 Q292.9 430.4 262.4 535.9 Q231.9 641.4 257.4 747.9 Q282.9 854.4 359.9 935.4 Q430.9 1008.4 525.4 1039.9 Q619.9 1071.4 717.9 1056.9 Q815.9 1042.4 896.9 983.4 L678.9 754.4 Q675.9 751.4 672.9 752.4 Q667.9 753.4 655.9 753.4 Q612.9 751.4 583.4 720.9 Q553.9 690.4 554.9 647.4 Q556.9 605.4 587.4 576.9 Q617.9 548.4 660.9 549.4 Q702.9 550.4 731.9 580.9 Q760.9 611.4 759.9 653.4 Q759.9 664.4 757.9 670.4 Q756.9 674.4 758.9 676.4 L1031.9 962.4 Q1043.9 974.4 1043.4 989.4 Q1042.9 1004.4 1032.9 1016.4 L1018.9 1030.4 Q915.9 1128.4 779.9 1160.4 Q643.9 1192.4 509.9 1153.4 Q375.9 1114.4 277.9 1011.4 Q179.9 908.4 147.9 772.9 Q115.9 637.4 154.4 503.4 Q192.9 369.4 296.9 271.4 Q399.9 173.4 535.9 140.9 Q671.9 108.4 805.9 146.9 Q939.9 185.4 1036.9 289.4 Z"),
                    fill = ink,
                )
            }
        }.build()
    }

    /** This phone, in the output sheet. */
    val DevicePhone: ImageVector by lazy {
        ImageVector.Builder(
            name = "opaline_device_phone", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.25 4.0 L15.749 4.0 C15.887 4.0 15.999 4.112 15.999 4.25 L15.999 19.748 C15.999 19.886 15.887 19.998 15.749 19.998 L8.25 19.998 C8.112 19.998 8.0 19.886 8.0 19.748 L8.0 4.25 C8.0 4.112 8.112 4.0 8.25 4.0 Z"),
                fill = ink,
                fillAlpha = 0.29f,
            )
            addPath(
                pathData = addPathNodes("M11.996 6.0 C12.41 6.0 12.746 5.664 12.746 5.25 C12.746 4.835 12.41 4.5 11.996 4.5 C11.582 4.5 11.246 4.835 11.246 5.25 C11.246 5.664 11.582 6.0 11.996 6.0 Z"),
                fill = ink,
            )
            addPath(
                pathData = addPathNodes("M7.302 2.0 L16.701 2.0 C17.419 2.0 18.001 2.582 18.001 3.3 L18.001 20.698 C18.001 21.416 17.419 21.998 16.701 21.998 L7.302 21.998 C6.584 21.998 6.002 21.416 6.002 20.698 L6.002 3.3 C6.002 2.582 6.584 2.0 7.302 2.0 Z"),
                stroke = ink,
                strokeLineWidth = 1.4f,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.0 18.999 L14.0 18.999"),
                stroke = ink,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** Earbuds, in the output sheet. */
    val DeviceEarbuds: ImageVector by lazy {
        ImageVector.Builder(
            name = "opaline_device_earbuds", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.286 3.664 C8.908 3.441 9.552 3.463 10.129 3.669 C10.131 3.672 10.133 3.675 10.135 3.678 C10.85 3.936 11.46 4.479 11.79 5.191 C12.355 6.409 12.395 7.826 11.7 9.03 C11.099 10.071 10.08 10.718 8.949 10.95 C8.079 11.128 7.174 10.882 6.517 10.321 C6.513 10.321 6.509 10.32 6.505 10.32 C6.161 10.025 5.884 9.646 5.718 9.194 C5.291 8.034 5.357 6.703 6.021 5.553 C6.547 4.64 7.363 3.996 8.286 3.664 Z M2.449 1.848 C4.148 0.15 6.982 0.518 8.922 2.227 C8.567 2.252 8.208 2.325 7.852 2.453 C6.655 2.883 5.592 3.722 4.906 4.91 C4.041 6.409 3.959 8.14 4.51 9.638 C4.514 9.65 4.519 9.661 4.523 9.673 C4.011 9.395 3.528 9.038 3.099 8.609 C1.162 6.672 0.665 3.633 2.449 1.848 Z M10.675 6.082 C10.177 5.795 9.334 6.322 8.792 7.259 C8.25 8.197 8.216 9.19 8.714 9.478 C9.212 9.765 10.054 9.238 10.596 8.301 C11.137 7.364 11.173 6.37 10.675 6.082 Z"),
                fill = ink,
            )
            addPath(
                pathData = addPathNodes("M3.309 10.433 C4.222 11.044 5.274 11.46 6.342 11.6 C6.765 11.828 7.224 11.984 7.693 12.066 L7.693 16.0 C7.693 17.211 6.711 18.192 5.501 18.193 C4.29 18.193 3.309 17.211 3.309 16.0 L3.309 10.433 Z"),
                fill = ink,
                fillAlpha = 0.29f,
            )
            addPath(
                pathData = addPathNodes("M15.579 8.664 C14.957 8.441 14.314 8.463 13.737 8.669 C13.736 8.672 13.733 8.675 13.731 8.678 C13.016 8.936 12.406 9.481 12.076 10.192 C11.512 11.411 11.471 12.827 12.166 14.03 C12.768 15.072 13.786 15.719 14.917 15.95 C15.788 16.128 16.693 15.882 17.351 15.321 C17.354 15.321 17.356 15.32 17.359 15.319 C17.703 15.024 17.981 14.646 18.147 14.193 C18.574 13.033 18.509 11.703 17.845 10.553 C17.318 9.64 16.503 8.996 15.579 8.664 Z M21.416 6.848 C19.717 5.149 16.882 5.517 14.942 7.227 C15.297 7.252 15.657 7.325 16.014 7.453 C17.21 7.883 18.273 8.722 18.959 9.91 C19.824 11.409 19.906 13.14 19.355 14.638 C19.351 14.649 19.346 14.66 19.342 14.672 C19.854 14.394 20.337 14.038 20.767 13.608 C22.703 11.671 23.2 8.633 21.416 6.848 Z M13.19 11.083 C13.689 10.795 14.532 11.322 15.073 12.26 C15.615 13.197 15.649 14.19 15.151 14.477 C14.653 14.765 13.811 14.238 13.269 13.301 C12.728 12.363 12.693 11.371 13.19 11.083 Z"),
                fill = ink,
            )
            addPath(
                pathData = addPathNodes("M20.557 15.435 C19.643 16.045 18.592 16.46 17.524 16.6 C17.101 16.829 16.641 16.985 16.172 17.067 L16.172 21.0 C16.172 22.211 17.154 23.192 18.364 23.193 C19.575 23.193 20.556 22.211 20.557 21.0 L20.557 15.435 Z"),
                fill = ink,
                fillAlpha = 0.29f,
            )
        }.build()
    }

    /** The player's like button, not liked: the heart in outline. */
    val Like: ImageVector by lazy { heart("listen_music_un_like", filled = false) }

    /** The player's like button, liked: the same heart, filled. */
    val LikeFilled: ImageVector by lazy { heart("listen_music_like", filled = true) }

    /**
     * The heart of `ic_listen_music_like` / `ic_listen_music_un_like`.
     *
     * Those drawables are a 28-unit disc with the heart inside it. The player
     * already draws the disc — it is what carries the artwork's blend — so only
     * the heart is taken, in a window cut from the middle of the original that
     * leaves it the same share of the button it had of its own disc.
     */
    private fun heart(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = HEART_WINDOW, viewportHeight = HEART_WINDOW,
        ).apply {
            group(
                translationX = -(HEART_DISC - HEART_WINDOW) / 2f,
                translationY = -(HEART_DISC - HEART_WINDOW) / 2f,
            ) {
                addPath(
                    pathData = addPathNodes("M18.98 14.51c0.55-0.57 0.85-1.33 0.85-2.12s-0.3-1.55-0.85-2.12c-0.27-0.27-0.59-0.5-0.94-0.65-0.36-0.15-0.74-0.22-1.12-0.22-0.39 0-0.77 0.07-1.12 0.22-0.36 0.15-0.68 0.38-0.95 0.65l-0.83 0.9-0.87-0.9c-0.27-0.27-0.6-0.5-0.95-0.64-0.35-0.16-0.73-0.23-1.12-0.23-0.38 0-0.76 0.07-1.12 0.23C9.61 9.78 9.3 10 9.02 10.27c-0.55 0.57-0.85 1.33-0.85 2.12 0 0.8 0.3 1.55 0.85 2.12l4.96 5.1 5-5.1Z"),
                    fill = if (filled) ink else null,
                    stroke = ink,
                    strokeLineWidth = 1.07f,
                    strokeLineMiter = 10f,
                )
            }
        }.build()

    /** The originals' viewport, and the square of it the heart is drawn from. */
    private const val HEART_DISC = 28f
    private const val HEART_WINDOW = 15.6f
}
