package com.csu.pharmacie.utils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;

public class ExcelUtils {

    /**
     * Lit la valeur d'une cellule Excel quel que soit son type (STRING, NUMERIC, BLANK, FORMULA).
     */
    public static String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                // Convertir le nombre en string sans décimales inutiles
                double numVal = cell.getNumericCellValue();
                if (numVal == Math.floor(numVal) && !Double.isInfinite(numVal)) {
                    return String.valueOf((long) numVal);
                }
                return String.valueOf(numVal);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        double fVal = cell.getNumericCellValue();
                        if (fVal == Math.floor(fVal) && !Double.isInfinite(fVal)) {
                            return String.valueOf((long) fVal);
                        }
                        return String.valueOf(fVal);
                    } catch (Exception e2) {
                        return null;
                    }
                }
            case BLANK:
            default:
                return null;
        }
    }
}
