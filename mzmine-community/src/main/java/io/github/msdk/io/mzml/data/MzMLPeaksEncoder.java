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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Deflater;
import io.github.msdk.MSDKException;
import io.github.msdk.datamodel.Chromatogram;
import io.github.msdk.datamodel.MsScan;
import io.github.msdk.io.mzml.util.MSNumpress;
import net.csibio.aird.compressor.ComboComp;
import net.csibio.aird.compressor.bytecomp.ZstdWrapper;
import net.csibio.aird.compressor.intcomp.VarByteWrapper;
import net.csibio.aird.compressor.sortedintcomp.IntegratedVarByteWrapper;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * Abstract MzMLPeaksEncoder class.
 * </p>
 */
public abstract class MzMLPeaksEncoder {
    private static final Logger logger = LoggerFactory.getLogger(MzMLPeaksEncoder.class);
    private static final int precision = 100000; //m/z, rt
    private static final int intPrecision = 10;

    /**
     * <p>
     * encodeDouble.
     * </p>
     *
     * @param data        an array of double.
     * @param compression a {@link io.github.msdk.io.mzml.data.MzMLCompressionType} object.
     * @return an array of byte.
     * @throws io.github.msdk.MSDKException if any.
     */
    public static byte[] encodeDouble(double[] data, MzMLCompressionType compression)
            throws MSDKException {
        byte[] encodedData ;
        int encodedBytes;

        // MSNumpress compression if required
        switch (compression) {
            case NUMPRESS_LINPRED:
            case NUMPRESS_LINPRED_ZLIB:
                // Set encodedData's array to the maximum possible size, truncate it later
                encodedData = new byte[8 + (data.length * 5)];
                encodedBytes = MSNumpress.encodeLinear(data, data.length, encodedData,
                        MSNumpress.optimalLinearFixedPoint(data, data.length));
                if (encodedBytes < 0)
                    throw new MSDKException("MSNumpress linear encoding failed");
                encodedData = Arrays.copyOf(encodedData, encodedBytes);
                break;
            case NUMPRESS_POSINT:
            case NUMPRESS_POSINT_ZLIB:
                encodedData = new byte[data.length * 5];
                encodedBytes = MSNumpress.encodePic(data, data.length, encodedData);
                if (encodedBytes < 0)
                    throw new MSDKException("MSNumpress positive integer encoding failed");
                encodedData = Arrays.copyOf(encodedData, encodedBytes);
                break;
            case NUMPRESS_SHLOGF:
            case NUMPRESS_SHLOGF_ZLIB:
                encodedData = new byte[8 + (data.length * 2)];
                encodedBytes = MSNumpress.encodeSlof(data, data.length, encodedData,
                        MSNumpress.optimalSlofFixedPoint(data, data.length));
                if (encodedBytes < 0)
                    throw new MSDKException("MSNumpress short floating logarithm encoding failed");
                encodedData = Arrays.copyOf(encodedData, encodedBytes);
                break;
            default:
                ByteBuffer buffer = ByteBuffer.allocate(data.length * 8);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                for (double d : data) {
                    buffer.putDouble(d);
                }
                encodedData = buffer.array();
                break;
        }

        // Zlib Compression if necessary
        return switch (compression) {
            case NUMPRESS_LINPRED_ZLIB, NUMPRESS_POSINT_ZLIB, NUMPRESS_SHLOGF_ZLIB, ZLIB -> {
                byte[] tmp = ZlibCompress(encodedData);
                yield Base64.getEncoder().encode(tmp);
            }
            default -> Base64.getEncoder().encode(encodedData);
        };

    }

    /**
     * <p>
     * encodeFloat.
     * </p>
     *
     * @param data        an array of float.
     * @param compression a {@link io.github.msdk.io.mzml.data.MzMLCompressionType} object.
     * @return an array of byte.
     * @throws io.github.msdk.MSDKException if any.
     */
    public static byte[] encodeFloat(float[] data, MzMLCompressionType compression)
            throws MSDKException {
        byte[] encodedData;

        // MSNumpress compression if required
        switch (compression) {
            case NUMPRESS_LINPRED:
            case NUMPRESS_LINPRED_ZLIB:
            case NUMPRESS_POSINT:
            case NUMPRESS_POSINT_ZLIB:
            case NUMPRESS_SHLOGF:
            case NUMPRESS_SHLOGF_ZLIB:
                throw new MSDKException("MSNumpress compression not supported for float values");
            default:
                ByteBuffer buffer = ByteBuffer.allocate(data.length * 4);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                for (float f : data) {
                    buffer.putFloat(f);
                }
                encodedData = buffer.array();
                break;
        }

        // Zlib Compression if necessary
        if (compression == MzMLCompressionType.ZLIB) {
            byte[] tmp = ZlibCompress(encodedData);
            return Base64.getEncoder().encode(tmp);
        }
        return Base64.getEncoder().encode(encodedData);

    }

    /**
     * Compressed source data using the Deflate algorithm.
     *
     * @param uncompressedData Data to be compressed
     * @return Compressed data
     */
    private static byte[] ZlibCompress(byte[] uncompressedData) {
        byte[] data; // Decompress the data

        // create a temporary byte array big enough to hold the compressed data
        // with the worst compression (the length of the initial (uncompressed) data)
        // EDIT: if it turns out this byte array was not big enough, then double its size and try again.
        byte[] temp = new byte[uncompressedData.length / 2];
        int compressedBytes = temp.length;
        while (compressedBytes == temp.length) {
            // compress
            temp = new byte[temp.length * 2];
            Deflater compresser = new Deflater();
            compresser.setInput(uncompressedData);
            compresser.finish();
            compressedBytes = compresser.deflate(temp);
        }

        // create a new array with the size of the compressed data (compressedBytes)
        data = new byte[compressedBytes];
        System.arraycopy(temp, 0, data, 0, compressedBytes);

        return data;
    }

    public static Pair<byte[], byte[]> comboCompEncode(MsScan scan) {
        double[] mzData = scan.getMzValues();
        float[] intData = scan.getIntensityValues();
        var size = mzData.length;
        if (size == 0) {
            return Pair.of(new byte[0], new byte[0]);
        }

        int[] mzArray = new int[size];
        int[] intArray = new int[size];
        int j = 0;
        for (int t = 0; t < size; t++) {
            if (intData[t] == 0) continue;
            mzArray[j] = fetchMz(mzData[t]);
            intArray[j] = fetchIntensity(intData[t]);
            j++;
        }

        int[] mzSubArray = new int[j];
        System.arraycopy(mzArray, 0, mzSubArray, 0, j);
        int[] intSubArray = new int[j];
        System.arraycopy(intArray, 0, intSubArray, 0, j);

         // encode mz, int
        byte[] compressedMzArray;
        byte[] compressedIntArray;
        if (mzSubArray.length == 0) {
            compressedMzArray = new byte[0];
        } else {
            compressedMzArray = ComboComp.encode(new IntegratedVarByteWrapper(), new ZstdWrapper(), mzSubArray);
        }
        if (intSubArray.length == 0) {
            compressedIntArray = new byte[0];
        } else {
            compressedIntArray = ComboComp.encode(new VarByteWrapper(), new ZstdWrapper(), intSubArray);
        }

        return Pair.of(Base64.getEncoder().encode(compressedMzArray), Base64.getEncoder().encode(compressedIntArray));
    }

    public static Pair<byte[], byte[]> comboCompEncode(Chromatogram chromatogram) {
        float[] rtData = chromatogram.getRetentionTimes(null);
        float[] intData = chromatogram.getIntensityValues();
        var size = rtData.length;
        if (size == 0) {
            return Pair.of(new byte[0], new byte[0]);
        }

        int[] rtArray = new int[size];
        int[] intArray = new int[size];
        for (int t = 0; t < size; t++) {
            rtArray[t] = fetchRt(rtData[t]);
            intArray[t] = fetchIntensity(intData[t]);
        }
        byte[] compressedRtArray = ComboComp.encode(new IntegratedVarByteWrapper(), new ZstdWrapper(), rtArray);
        byte[] compressedIntArray = ComboComp.encode(new VarByteWrapper(), new ZstdWrapper(), intArray);

        return Pair.of(Base64.getEncoder().encode(compressedRtArray), Base64.getEncoder().encode(compressedIntArray));
    }

    private static int fetchMz(double target) {
        int result = -1;
        try {
            result = (int) Math.round(target * precision);
        } catch (Exception e) {
            logger.error("Exception occurred while fetching mz", e);
        }
        return result;
    }

    private static int fetchIntensity(double target) {
        int result;
        double ori = target * intPrecision;
        if (ori <= Integer.MAX_VALUE) {
            result = (int) Math.round(ori); // 四舍五入到最接近的整数
        } else {
            result = (int) (-Math.round(Math.log(ori) / Math.log(2) * 100000));
        }
        return result;
    }

    private static int fetchRt(double target) {
        int result = -1;
        try {
            result = (int) Math.round(target * precision);
        } catch (Exception e) {
            logger.error("Exception occurred while fetching rt", e);
        }
        return result;
    }

}
