/*
* Copyright (C) 2015 DarkKat
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.temasek;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.drawable.Drawable;

public class ColorHelper {

    public static boolean isColorDark(int color) {
        double a = 1- (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255;
        if (a < 0.5) {
            return false;
        } else {
            return true;
        }
    }

    public static int getLightenOrDarkenColor(int originalColor) {
        boolean isOriginalColorDark = isColorDark(originalColor);
        float factor = isOriginalColorDark ? 0.1f : 0.8f;
        int a = Color.alpha(originalColor);
        int r = Color.red(originalColor);
        int g = Color.green(originalColor);
        int b = Color.blue(originalColor);
        int newColor;

        if (isOriginalColorDark) {
            newColor = Color.argb(a,
                    (int) ((r * (1 - factor) / 255 + factor) * 255),
                    (int) ((g * (1 - factor) / 255 + factor) * 255),
                    (int) ((b * (1 - factor) / 255 + factor) * 255));
        } else {
            newColor = Color.argb(a,
                    Math.max((int) (r * factor), 0),
                    Math.max((int) (g * factor), 0),
                    Math.max((int) (b * factor), 0));
        }
        return newColor;
    }

    public static ColorMatrixColorFilter getColorFilter(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;

        ColorMatrix cm = new ColorMatrix(new float[] {
                r, 0, 0, 0, 0,
                0, g, 0, 0, 0,
                0, 0, b, 0, 0,
                0, 0, 0, 1, 0,
        });
        ColorMatrixColorFilter cf = new ColorMatrixColorFilter(cm);
        return cf;
    }
}
