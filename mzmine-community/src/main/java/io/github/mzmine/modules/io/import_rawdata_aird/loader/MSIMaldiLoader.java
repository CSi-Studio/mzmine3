package io.github.mzmine.modules.io.import_rawdata_aird.loader;

import com.google.common.collect.Range;
import io.github.mzmine.datamodel.MassSpectrumType;
import io.github.mzmine.datamodel.PolarityType;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.impl.DDAMsMsInfoImpl;
import io.github.mzmine.datamodel.impl.SimpleImagingScan;
import io.github.mzmine.datamodel.impl.SimpleScan;
import io.github.mzmine.modules.io.import_rawdata_aird.AirdImportTask;
import io.github.mzmine.modules.io.import_rawdata_imzml.Coordinates;
import io.github.mzmine.taskcontrol.TaskStatus;
import net.csibio.aird.bean.DDAMs;
import net.csibio.aird.bean.common.Spectrum;
import net.csibio.aird.bean.msi.SpectraPosition;
import net.csibio.aird.enums.MsLevel;
import net.csibio.aird.parser.DDAParser;
import net.csibio.aird.parser.MSIMaldiParser;

import java.util.List;
import io.github.mzmine.util.scans.ScanUtils;

public class MSIMaldiLoader {

  public static void load(AirdImportTask task, MSIMaldiParser parser) throws Exception {
    List<Spectrum> msList = parser.readAllToMemory();
    var spectraPosition = parser.getSpectraPosition();
    var airdInfo = parser.getAirdInfo();
    PolarityType polarityType = PolarityType.UNKNOWN;
    MassSpectrumType massSpectrumType = MassSpectrumType.ANY;
    if (airdInfo.getPolarity().equals(PolarityType.POSITIVE.name())) {
      polarityType = PolarityType.POSITIVE;
    }
    if (airdInfo.getPolarity().equals(PolarityType.NEGATIVE.name())) {
      polarityType = PolarityType.NEGATIVE;
    }
    if (airdInfo.getMsType().equals(MassSpectrumType.PROFILE.name())) {
      massSpectrumType = MassSpectrumType.PROFILE;
    }
    if (airdInfo.getMsType().equals(MassSpectrumType.CENTROIDED.name())) {
      massSpectrumType = MassSpectrumType.CENTROIDED;
    }
    if (msList.isEmpty()) {
      task.setStatus(TaskStatus.ERROR);
      task.setErrorMessage("Parsing Cancelled, No MS1 Scan found.");
      return;
    }
    int scanNumber = 0;
    for (Spectrum ms1 : msList) {
      massSpectrumType = ScanUtils.detectSpectrumType(ms1.getMzs(),ms1.getInts());
      SimpleImagingScan ms1Scan = buildSimpleScan(task, ms1, scanNumber, null, MsLevel.MS1.getCode(),polarityType, massSpectrumType, spectraPosition);
      task.parsedScans++;
      task.newMZmineFile.addScan(ms1Scan);
      scanNumber++;
//      if (ms1.getMs2List() != null && !ms1.getMs2List().isEmpty()) {
//        for (int j = 0; j < ms1.getMs2List().size(); j++) {
//          Spectrum ms2 = ms1.getMs2List().get(j);
//          SimpleImagingScan ms2Scan = buildSimpleScan(task, ms2, scanNumber, ms1Scan, MsLevel.MS2.getCode(), spectraPosition);
//          task.parsedScans++;
//          task.newMZmineFile.addScan(ms2Scan);
//        }
//      }
    }
  }

  private static SimpleImagingScan buildSimpleScan(AirdImportTask task, Spectrum spectrum, int scanNumber, Scan parentScan,
     int msLevel, PolarityType polarityType, MassSpectrumType massSpectrumType, SpectraPosition spectraPosition) {

    Range mzRange = null;
    if (spectrum != null && spectrum.getMzs().length != 0) {
      mzRange = Range.closed(spectrum.getMzs()[0],
              spectrum.getMzs()[spectrum.getMzs().length - 1]);
    }

//    DDAMsMsInfoImpl msMsInfo = null;
//    if (msLevel == MsLevel.MS2.getCode()) {
//      msMsInfo = BaseLoader.buildMsMsInfo(, ddaMs.getEnergy(), ddaMs.getRange(),
//          parentScan);
//    }

    double precursorMZ = 0;
    int precursorCharge = 0;
    //int scanNumber = ddaMs.getNum();
    Coordinates coordinates = new Coordinates(spectraPosition.getX()[scanNumber] - 1,spectraPosition.getY()[scanNumber] - 1,0);
    SimpleImagingScan scan = new SimpleImagingScan(task.newMZmineFile, scanNumber+1, msLevel,0,
            precursorMZ, precursorCharge, spectrum.getMzs(), spectrum.getInts(), massSpectrumType,polarityType,
            "", mzRange, coordinates);

    return scan;

  }
}
