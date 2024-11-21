/*
 * Copyright (c) 2004-2024 The MZmine Development Team
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.mzmine.modules.io.import_rawdata_mzml.msdk.data;

import com.google.common.io.LittleEndianDataInputStream;
import io.github.msdk.MSDKException;
import io.github.mzmine.modules.io.import_rawdata_mzml.msdk.util.MSNumpress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.csibio.aird.compressor.ComboComp;
import net.csibio.aird.compressor.bytecomp.ZstdWrapper;
import net.csibio.aird.compressor.intcomp.VarByteWrapper;
import net.csibio.aird.compressor.sortedintcomp.IntegratedVarByteWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * <p>
 * MzMLIntensityPeaksDecoder class.
 * </p>
 */
public class MzMLPeaksDecoder {
    private static final Logger logger = Logger.getLogger(MzMLPeaksDecoder.class.getName());
    private static final double precision = 100000d; //m/z, rt
    private static final double intPrecision = 10d;

    /**
     * Converts a base64 encoded mz or intensity string used in mzML files to an array of doubles. If
     * the original precision was 32 bit, you still get doubles as output.
     *
     * @param binaryDataInfo meta-info about encoded data
     * @return a double array containing the decoded values
     */
    public static double[] decodeToDouble(MzMLBinaryDataInfo binaryDataInfo) {
        return decodeToDoubleAsArray(binaryDataInfo.getXmlBinaryContent(), binaryDataInfo);
    }

    /**
     * Converts a base64 encoded mz or intensity string used in mzML files to an array of doubles. If
     * the original precision was 32 bit, you still get doubles as output.
     *
     * @param binaryDataInfo meta-info about encoded data
     * @return a double array containing the decoded values
     */
    private static double[] decodeToDoubleAsArray(final String binaryData,
                                                  final MzMLBinaryDataInfo binaryDataInfo) {
        return decodeToDoubleAsArray(binaryData, binaryDataInfo, null);
    }

    /**
     * Converts a base64 encoded mz or intensity string used in mzML files to an array of doubles. If
     * the original precision was 32 bit, you still get doubles as output.
     *
     * @param binaryDataInfo meta-info about encoded data
     * @return a double array containing the decoded values
     */
    public static double[] decodeToDoubleAsArray(final String binaryData,
                                                 final MzMLBinaryDataInfo binaryDataInfo, double @Nullable [] data) {

        int lengthIn = binaryDataInfo.getEncodedLength();
        int numPoints = binaryDataInfo.getArrayLength();
        // for some reason there sometimes might be zero length <peaks> tags
        // (ms2 usually)
        // in this case we just return an empty result
        if (lengthIn == 0) {
            return new double[0];
        }

        if (data == null || data.length < numPoints) {
            data = new double[numPoints];
        }

        byte[] bytes = Base64.getDecoder().decode(binaryData);

        if (binaryDataInfo.getCompressionType().isAirdComboComp()) {
            // if CVParam states the data is compressed
            comboCompDecodeDouble(bytes, binaryDataInfo, data);
            return data;
        }

        if (binaryDataInfo.getCompressionType().isZlibCompressed()) {
            // if CVParam states the data is compressed
            bytes = decompress(bytes);
        }

        if (binaryDataInfo.getCompressionType().isNumpress()) {
            try {
                data = decompressIfNumpress(binaryDataInfo, data, bytes);
                return data;
            } catch (MSDKException e) {
                logger.warning(STR."Could not decompress numpress \{binaryDataInfo.getCompressionType()}");
            }
        }

        // otherwise directly read numbers
        convertToDoubles(binaryDataInfo, bytes, data);
        return data;
    }

    private static void convertToDoubles(final MzMLBinaryDataInfo binaryDataInfo, final byte[] bytes,
                                         final double[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        switch (binaryDataInfo.getBitLength()) {
            case THIRTY_TWO_BIT_FLOAT -> {
                for (int i = 0; i < data.length; i++) {
                    data[i] = buffer.getFloat();
                }
            }
            case THIRTY_TWO_BIT_INTEGER -> {
                for (int i = 0; i < data.length; i++) {
                    data[i] = buffer.getInt();
                }
            }
            case SIXTY_FOUR_BIT_FLOAT -> {
                for (int i = 0; i < data.length; i++) {
                    data[i] = buffer.getDouble();
                }
            }
            case SIXTY_FOUR_BIT_INTEGER -> {
                for (int i = 0; i < data.length; i++) {
                    data[i] = buffer.getLong();
                }
            }
        }
    }

    private static byte[] decompress(byte[] compressedData) {
        byte[] decompressedData = null;

        // using a ByteArrayOutputStream to not having to define the result array size beforehand
        Inflater decompressor = new Inflater();

        decompressor.setInput(compressedData);
        // Create an expandable byte array to hold the decompressed data
        // use 2.5 times size to limit number of backing array resize by ByteArrayOutputStream.ensureCapacity
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(
                (int) (compressedData.length * 2.5))) {
            byte[] buf = new byte[1024];
            while (!decompressor.finished()) {
                try {
                    int count = decompressor.inflate(buf);
                    if (count == 0 && decompressor.needsInput()) {
                        break;
                    }
                    bos.write(buf, 0, count);
                } catch (DataFormatException e) {
                    decompressor.end();
                    throw new IllegalStateException(
                            "Encountered wrong data format " + "while trying to decompress binary data!", e);
                }
            }

            // Get the decompressed data
            decompressedData = bos.toByteArray();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error while decompressing data", e);
        }
        decompressor.end();
        return decompressedData;
    }

    private static double[] decompressIfNumpress(final MzMLBinaryDataInfo binaryDataInfo,
                                                 final double[] data, byte[] bytes) throws MSDKException {
        int numDecodedDoubles;
        switch (binaryDataInfo.getCompressionType()) {
            case NUMPRESS_LINPRED:
            case NUMPRESS_LINPRED_ZLIB:
                numDecodedDoubles = MSNumpress.decodeLinear(bytes, bytes.length, data);
                if (numDecodedDoubles < 0) {
                    throw new MSDKException("MSNumpress linear decoder failed");
                }
                return data;
            case NUMPRESS_POSINT:
            case NUMPRESS_POSINT_ZLIB:
                numDecodedDoubles = MSNumpress.decodePic(bytes, bytes.length, data);
                if (numDecodedDoubles < 0) {
                    throw new MSDKException("MSNumpress positive integer decoder failed");
                }
                return data;
            case NUMPRESS_SHLOGF:
            case NUMPRESS_SHLOGF_ZLIB:
                numDecodedDoubles = MSNumpress.decodeSlof(bytes, bytes.length, data);
                if (numDecodedDoubles < 0) {
                    throw new MSDKException("MSNumpress short logged float decoder failed");
                }
                return data;
            default:
                break;
        }
        return null;
    }

    private static void comboCompDecodeDouble(byte[] bytes, MzMLBinaryDataInfo binaryDataInfo, double[] data){
        MzMLArrayType arrayType = binaryDataInfo.getArrayType();
        if (arrayType == MzMLArrayType.MZ || arrayType == MzMLArrayType.TIME) {
            int[] decodeArray = ComboComp.decode(new IntegratedVarByteWrapper(), new ZstdWrapper(), bytes);
            for (int i = 0; i < decodeArray.length; i++) {
                data[i] = decodeArray[i] / precision;
            }
        } else if (arrayType == MzMLArrayType.INTENSITY) {
            int[] decodeArray = ComboComp.decode(new VarByteWrapper(), new ZstdWrapper(), bytes);
            for (int i = 0; i < decodeArray.length; i++) {
                data[i] = decodeArray[i] / intPrecision;
            }
        }
    }
}