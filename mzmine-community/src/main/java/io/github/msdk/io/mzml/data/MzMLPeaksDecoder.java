/*
 * (C) Copyright 2015-2016 by MSDK Development Team
 *
 * This software is dual-licensed under either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1 as published by the Free
 * Software Foundation
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by the Eclipse Foundation.
 */

package io.github.msdk.io.mzml.data;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.InflaterInputStream;

import net.csibio.aird.compressor.ComboComp;
import net.csibio.aird.compressor.bytecomp.ZstdWrapper;
import net.csibio.aird.compressor.intcomp.VarByteWrapper;
import net.csibio.aird.compressor.sortedintcomp.IntegratedVarByteWrapper;
import org.apache.commons.io.IOUtils;

import com.google.common.io.LittleEndianDataInputStream;

import io.github.msdk.MSDKException;
import io.github.msdk.io.mzml.util.ByteBufferInputStream;
import io.github.msdk.io.mzml.util.MSNumpress;

/**
 * <p>
 * MzMLIntensityPeaksDecoder class.
 * </p>
 */
public class MzMLPeaksDecoder {
    private static final Logger logger = Logger.getLogger(MzMLPeaksDecoder.class.getName());
    private static final double mzPrecision = 100000d;
    private static final double intPrecision = 10d;

    /**
     * Converts a base64 encoded mz or intensity string used in mzML files to an array of floats. If
     * the original precision was 64 bit, you still get floats as output.
     *
     * @param binaryDataInfo meta-info about the compressed data
     * @param inputStream    a {@link java.io.InputStream} object.
     * @param data           an array of float.
     * @return a float array containing the decoded values
     * @throws java.io.IOException          if any.
     * @throws io.github.msdk.MSDKException if any.
     */
    public static float[] decodeToFloat(InputStream inputStream, MzMLBinaryDataInfo binaryDataInfo,
                                        float[] data) throws IOException, MSDKException {

        int lengthIn = binaryDataInfo.getEncodedLength();
        int numPoints = binaryDataInfo.getArrayLength();
        InputStream is = null;

        if (inputStream instanceof ByteBufferInputStream mappedByteBufferInputStream) {
            mappedByteBufferInputStream.constrain(binaryDataInfo.getPosition(), lengthIn);
            is = Base64.getDecoder().wrap(mappedByteBufferInputStream);
        } else {
            is = Base64.getDecoder().wrap(inputStream);
        }

        // for some reason there sometimes might be zero length <peaks> tags
        // (ms2 usually)
        // in this case we just return an empty result
        if (lengthIn == 0) {
            return new float[0];
        }

        InflaterInputStream iis;
        LittleEndianDataInputStream dis;
        byte[] bytes;

        if (data == null || data.length < numPoints)
            data = new float[numPoints];

        // first check for zlib compression, inflation must be done before
        // NumPress
        if (binaryDataInfo.getCompressionType() != null) {
            dis = switch (binaryDataInfo.getCompressionType()) {
                case ZLIB, NUMPRESS_LINPRED_ZLIB, NUMPRESS_POSINT_ZLIB, NUMPRESS_SHLOGF_ZLIB -> {
                    iis = new InflaterInputStream(is);
                    yield new LittleEndianDataInputStream(iis);
                }
                default -> new LittleEndianDataInputStream(is);
            };

            // Now we can check for NumPress or aird combo compression
            int numDecodedDoubles;
            switch (binaryDataInfo.getCompressionType()) {
                case AIRD_COMBOCOMP:
                    bytes = IOUtils.toByteArray(dis);
                    comboCompDecodeFloat(bytes, binaryDataInfo, data);
                    return data;
                case NUMPRESS_LINPRED:
                case NUMPRESS_LINPRED_ZLIB:
                    bytes = IOUtils.toByteArray(dis);
                    numDecodedDoubles = MSNumpress.decodeLinear(bytes, bytes.length, data);
                    if (numDecodedDoubles < 0) {
                        throw new MSDKException("MSNumpress linear decoder failed");
                    }
                    return data;
                case NUMPRESS_POSINT:
                case NUMPRESS_POSINT_ZLIB:
                    bytes = IOUtils.toByteArray(dis);
                    numDecodedDoubles = MSNumpress.decodePic(bytes, bytes.length, data);
                    if (numDecodedDoubles < 0) {
                        throw new MSDKException("MSNumpress positive integer decoder failed");
                    }
                    return data;
                case NUMPRESS_SHLOGF:
                case NUMPRESS_SHLOGF_ZLIB:
                    bytes = IOUtils.toByteArray(dis);
                    numDecodedDoubles = MSNumpress.decodeSlof(bytes, bytes.length, data);
                    if (numDecodedDoubles < 0) {
                        throw new MSDKException("MSNumpress short logged float decoder failed");
                    }
                    return data;
                default:
                    break;
            }
        } else {
            dis = new LittleEndianDataInputStream(is);
        }

        Integer precision = switch (binaryDataInfo.getBitLength()) {
            case THIRTY_TWO_BIT_FLOAT, THIRTY_TWO_BIT_INTEGER -> 32;
            case SIXTY_FOUR_BIT_FLOAT, SIXTY_FOUR_BIT_INTEGER -> 64;
            default -> {
                dis.close();
                throw new IllegalArgumentException(
                        "Precision MUST be specified and be either 32-bit or 64-bit, "
                                + "if MS-NUMPRESS compression was not used");
            }
        };

        try {
            switch (precision) {
                case (32): {
                    for (int i = 0; i < numPoints; i++) {
                        data[i] = dis.readFloat();
                    }
                    break;
                }
                case (64): {
                    for (int i = 0; i < numPoints; i++) {
                        data[i] = (float) dis.readDouble();
                    }
                    break;
                }
                default: {
                    dis.close();
                    throw new IllegalArgumentException(
                            "Precision can only be 32/64 bits, other values are not valid.");
                }
            }
        } catch (EOFException eof) {
            // If the stream reaches EOF unexpectedly, it is probably because the particular
            // scan/chromatogram didn't pass the Predicate
            throw new MSDKException(
                    "Couldn't obtain values. Please make sure the scan/chromatogram passes the Predicate.");
        } finally {
            dis.close();
        }

        return data;
    }

    /**
     * Converts a base64 encoded mz or intensity string used in mzML files to an array of doubles. If
     * the original precision was 32 bit, you still get doubles as output.
     *
     * @param binaryDataInfo meta-info about encoded data
     * @param inputStream    a {@link java.io.InputStream} object.
     * @param data           an array of double.
     * @return a double array containing the decoded values
     * @throws java.io.IOException          if any.
     * @throws io.github.msdk.MSDKException if any.
     */
    public static double[] decodeToDouble(InputStream inputStream, MzMLBinaryDataInfo binaryDataInfo,
                                          double[] data) throws IOException, MSDKException {

        int lengthIn = binaryDataInfo.getEncodedLength();
        int numPoints = binaryDataInfo.getArrayLength();

        InputStream is;

        if (inputStream instanceof ByteBufferInputStream mappedByteBufferInputStream) {
            mappedByteBufferInputStream.constrain(binaryDataInfo.getPosition(), lengthIn);
            is = Base64.getDecoder().wrap(mappedByteBufferInputStream);
        } else {
            is = Base64.getDecoder().wrap(inputStream);
        }

        // for some reason there sometimes might be zero length <peaks> tags
        // (ms2 usually)
        // in this case we just return an empty result
        if (lengthIn == 0) {
            return new double[0];
        }

        InflaterInputStream iis;
        LittleEndianDataInputStream dis;
        byte[] bytes;

        if (data == null || data.length < numPoints)
            data = new double[numPoints];

        // first check for zlib compression, inflation must be done before
        // NumPress
        if (binaryDataInfo.getCompressionType() != null) {
            dis = switch (binaryDataInfo.getCompressionType()) {
                case ZLIB, NUMPRESS_LINPRED_ZLIB, NUMPRESS_POSINT_ZLIB, NUMPRESS_SHLOGF_ZLIB -> {
                    iis = new InflaterInputStream(is);
                    yield new LittleEndianDataInputStream(iis);
                }
                default -> new LittleEndianDataInputStream(is);
            };

            // Now we can check for NumPress, Aird combo compression
            int numDecodedDoubles;
            switch (binaryDataInfo.getCompressionType()) {
                case AIRD_COMBOCOMP:
                    bytes = IOUtils.toByteArray(dis);
                    comboCompDecodeDouble(bytes, binaryDataInfo, data);
                    return data;
                case NUMPRESS_LINPRED:
                case NUMPRESS_LINPRED_ZLIB:
                    bytes = IOUtils.toByteArray(dis);
                    numDecodedDoubles = MSNumpress.decodeLinear(bytes, bytes.length, data);
                    if (numDecodedDoubles < 0) {
                        throw new MSDKException("MSNumpress linear decoder failed");
                    }
                    return data;
                case NUMPRESS_POSINT:
                case NUMPRESS_POSINT_ZLIB:
                    bytes = IOUtils.toByteArray(dis);
                    numDecodedDoubles = MSNumpress.decodePic(bytes, bytes.length, data);
                    if (numDecodedDoubles < 0) {
                        throw new MSDKException("MSNumpress positive integer decoder failed");
                    }
                    return data;
                case NUMPRESS_SHLOGF:
                case NUMPRESS_SHLOGF_ZLIB:
                    bytes = IOUtils.toByteArray(dis);
                    numDecodedDoubles = MSNumpress.decodeSlof(bytes, bytes.length, data);
                    if (numDecodedDoubles < 0) {
                        throw new MSDKException("MSNumpress short logged float decoder failed");
                    }
                    return data;
                default:
                    break;
            }
        } else {
            dis = new LittleEndianDataInputStream(is);
        }

        Integer precision = switch (binaryDataInfo.getBitLength()) {
            case THIRTY_TWO_BIT_FLOAT, THIRTY_TWO_BIT_INTEGER -> 32;
            case SIXTY_FOUR_BIT_FLOAT, SIXTY_FOUR_BIT_INTEGER -> 64;
            default -> {
                dis.close();
                throw new IllegalArgumentException(
                        "Precision MUST be specified and be either 32-bit or 64-bit, "
                                + "if MS-NUMPRESS compression was not used");
            }
        };

        try {
            switch (precision) {
                case (32): {
                    int asInt;

                    for (int i = 0; i < numPoints; i++) {
                        asInt = dis.readInt();
                        data[i] = Float.intBitsToFloat(asInt);
                    }
                    break;
                }
                case (64): {
                    long asLong;

                    for (int i = 0; i < numPoints; i++) {
                        asLong = dis.readLong();
                        data[i] = Double.longBitsToDouble(asLong);
                    }
                    break;
                }
            }
        } catch (EOFException eof) {
            // If the stream reaches EOF unexpectedly, it is probably because the particular
            // scan/chromatogram didn't pass the Predicate
            throw new MSDKException(
                    "Couldn't obtain values. Please make sure the scan/chromatogram passes the Predicate.");
        } finally {
            dis.close();
        }
        return data;
    }

    private static void comboCompDecodeFloat(byte[] bytes, MzMLBinaryDataInfo binaryDataInfo, float[] data) {
        MzMLArrayType arrayType = binaryDataInfo.getArrayType();
        if (arrayType == MzMLArrayType.INTENSITY) {
            int[] decodeArray = ComboComp.decode(new VarByteWrapper(), new ZstdWrapper(), bytes);
            for (int i = 0; i < decodeArray.length; i++) {
                data[i] = (float) (decodeArray[i] / intPrecision);
            }
        } else if (arrayType == MzMLArrayType.TIME || arrayType == MzMLArrayType.MZ) {
            int[] decodeArray = ComboComp.decode(new IntegratedVarByteWrapper(), new ZstdWrapper(), bytes);
            for (int i = 0; i < decodeArray.length; i++) {
                data[i] = (float) (decodeArray[i] / mzPrecision);
            }
        }
    }

    private static void comboCompDecodeDouble(byte[] bytes, MzMLBinaryDataInfo binaryDataInfo, double[] data) {
        MzMLArrayType arrayType = binaryDataInfo.getArrayType();
        if (arrayType == MzMLArrayType.MZ || arrayType == MzMLArrayType.TIME) {
            int[] decodeArray = ComboComp.decode(new IntegratedVarByteWrapper(), new ZstdWrapper(), bytes);
            for (int i = 0; i < decodeArray.length; i++) {
                data[i] = decodeArray[i] / mzPrecision;
            }
        } else if (arrayType == MzMLArrayType.INTENSITY) {
            int[] decodeArray = ComboComp.decode(new VarByteWrapper(), new ZstdWrapper(), bytes);
            for (int i = 0; i < decodeArray.length; i++) {
                data[i] = decodeArray[i] / intPrecision;
            }
        }
    }

}
