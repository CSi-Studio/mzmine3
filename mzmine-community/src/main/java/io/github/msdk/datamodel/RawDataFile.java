//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.github.msdk.datamodel;

import java.io.File;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

public interface RawDataFile {
    @Nonnull
    String getName();

    @Nonnull
    Optional<File> getOriginalFile();

    @Nonnull
    default String getOriginalFilename() {
        return "Unknown";
    }

    @Nonnull
    FileType getRawDataFileType();

    @Nonnull
    List<String> getMsFunctions();

    @Nonnull
    List<MsScan> getScans();

    @Nonnull
    List<Chromatogram> getChromatograms();

    void dispose();
}
