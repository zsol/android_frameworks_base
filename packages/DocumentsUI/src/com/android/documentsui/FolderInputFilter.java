/*
 * Copyright (C) 2015 The Oneplus Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Per article 5 of the Apache 2.0 License, some modifications to this code
 * were made by the Oneplus Project.
 *
 * Modifications Copyright (C) 2015 The Oneplus Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.android.documentsui;

import android.text.InputFilter;
import android.text.Spanned;

public class FolderInputFilter implements InputFilter {

    private static final String RESERVED_CHARS = "|\\?*<\":>+[]/'";

    public FolderInputFilter() {
    }

    @Override
    public CharSequence filter(CharSequence source, int start, int end,
            Spanned dest, int dstart, int dend) {

        if (source != null && RESERVED_CHARS.contains(("" + source))) {
            return "";
        }
        return null;
    }
}
