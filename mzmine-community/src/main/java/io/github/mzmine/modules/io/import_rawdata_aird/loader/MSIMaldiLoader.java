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
import net.csibio.aird.bean.msi.SpectraPosition;
import net.csibio.aird.enums.MsLevel;
import net.csibio.aird.parser.DDAParser;
import net.csibio.aird.parser.MSIMaldiParser;

import java.util.List;

public class MSIMaldiLoader {

  public static void load(AirdImportTask task, MSIMaldiParser parser) throws Exception {
    List<DDAMs> msList = parser.readAllToMemory();
    var spectraPosition = parser.getSpectraPosition();
    if (msList.isEmpty()) {
      task.setStatus(TaskStatus.ERROR);
      task.setErrorMessage("Parsing Cancelled, No MS1 Scan found.");
      return;
    }

    for (DDAMs ms1 : msList) {
      SimpleImagingScan ms1Scan = buildSimpleScan(task, ms1, null, MsLevel.MS1.getCode(),spectraPosition);
      task.parsedScans++;
      task.newMZmineFile.addScan(ms1Scan);
      if (ms1.getMs2List() != null && !ms1.getMs2List().isEmpty()) {
        for (int j = 0; j < ms1.getMs2List().size(); j++) {
          DDAMs ms2 = ms1.getMs2List().get(j);
          SimpleImagingScan ms2Scan = buildSimpleScan(task, ms2, ms1Scan, MsLevel.MS2.getCode(),spectraPosition);
          task.parsedScans++;
          task.newMZmineFile.addScan(ms2Scan);
        }
      }
    }
  }

  private static SimpleImagingScan buildSimpleScan(AirdImportTask task, DDAMs ddaMs, Scan parentScan,
     int msLevel, SpectraPosition spectraPosition) {

    PolarityType polarityType = null;
    MassSpectrumType massSpectrumType = null;

    if (ddaMs.getPolarity().equals(PolarityType.POSITIVE.name())) {
      polarityType = PolarityType.POSITIVE;
    }
    if (ddaMs.getPolarity().equals(PolarityType.NEGATIVE.name())) {
      polarityType = PolarityType.NEGATIVE;
    }
    if (ddaMs.getMsType().equals(MassSpectrumType.PROFILE.name())) {
      massSpectrumType = MassSpectrumType.PROFILE;
    }
    if (ddaMs.getMsType().equals(MassSpectrumType.CENTROIDED.name())) {
      massSpectrumType = MassSpectrumType.CENTROIDED;
    }

    Range mzRange = null;
    if (ddaMs.getSpectrum() != null && ddaMs.getSpectrum().getMzs().length != 0) {
      mzRange = Range.closed(ddaMs.getSpectrum().getMzs()[0],
          ddaMs.getSpectrum().getMzs()[ddaMs.getSpectrum().getMzs().length - 1]);
    }

    DDAMsMsInfoImpl msMsInfo = null;
    if (msLevel == MsLevel.MS2.getCode()) {
      msMsInfo = BaseLoader.buildMsMsInfo(ddaMs.getActivator(), ddaMs.getEnergy(), ddaMs.getRange(),
          parentScan);
    }

    SimpleScan msScan = new SimpleScan(task.newMZmineFile, ddaMs.getNum() + 1, msLevel,
        ddaMs.getRt().floatValue(), msMsInfo, ddaMs.getSpectrum().getMzs(),
        ddaMs.getSpectrum().getInts(), massSpectrumType, polarityType, ddaMs.getFilterString(),
        mzRange, ddaMs.getInjectionTime());

    double precursorMZ = 0;
    int precursorCharge = 0;
    int scanNumber = ddaMs.getNum();
    Coordinates coordinates = new Coordinates(spectraPosition.getX()[scanNumber] - 1,spectraPosition.getY()[scanNumber] - 1,0);
    SimpleImagingScan scan = new SimpleImagingScan(task.newMZmineFile,scanNumber + 1, msLevel,ddaMs.getRt().floatValue(),
            precursorMZ, precursorCharge, ddaMs.getSpectrum().getMzs(), ddaMs.getSpectrum().getInts(), massSpectrumType,polarityType,
            ddaMs.getFilterString(), mzRange, coordinates);

    return scan;

  }
}
