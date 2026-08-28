package com.github.laplusijns.recognition;

import java.util.List;
import java.util.Set;

public interface MissingFieldVisionVerifier {
    BusinessCardRecognition verify(
            LayoutDocument layout,
            BusinessCardRecognition currentFields,
            Set<FieldType> missingFields,
            List<CroppedImage> fullPageImages);
}
