/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.layout.binary;

import com.eventsourcing.layout.Deserializer;
import com.eventsourcing.layout.types.BooleanTypeHandler;

import java.nio.ByteBuffer;

public class BooleanBinaryDeserializer implements Deserializer<Boolean, BooleanTypeHandler> {

    @Override
    public Boolean deserialize(ByteBuffer buffer) {
        return buffer.get() == 1;
    }

}
