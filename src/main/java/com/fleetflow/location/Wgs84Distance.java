package com.fleetflow.location;

/** Vincenty inverse solution on the WGS84 ellipsoid, in metres. */
public final class Wgs84Distance {
    private static final double A = 6_378_137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double B = A * (1 - F);

    private Wgs84Distance() { }

    public static double metres(double latitude1, double longitude1, double latitude2, double longitude2) {
        if (latitude1 == latitude2 && longitude1 == longitude2) return 0;
        double reduced1 = Math.atan((1 - F) * Math.tan(Math.toRadians(latitude1)));
        double reduced2 = Math.atan((1 - F) * Math.tan(Math.toRadians(latitude2)));
        double sin1 = Math.sin(reduced1), cos1 = Math.cos(reduced1);
        double sin2 = Math.sin(reduced2), cos2 = Math.cos(reduced2);
        double original = Math.toRadians(longitude2 - longitude1);
        double lambda = original;
        for (int iteration = 0; iteration < 100; iteration++) {
            double sinLambda = Math.sin(lambda), cosLambda = Math.cos(lambda);
            double sinSigma = Math.hypot(cos2 * sinLambda,
                    cos1 * sin2 - sin1 * cos2 * cosLambda);
            if (sinSigma == 0) return 0;
            double cosSigma = sin1 * sin2 + cos1 * cos2 * cosLambda;
            double sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cos1 * cos2 * sinLambda / sinSigma;
            double cosSqAlpha = 1 - sinAlpha * sinAlpha;
            double cos2SigmaM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sin1 * sin2 / cosSqAlpha;
            double c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha));
            double next = original + (1 - c) * F * sinAlpha *
                    (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma *
                    (-1 + 2 * cos2SigmaM * cos2SigmaM)));
            if (Math.abs(next - lambda) < 1e-12) {
                double uSq = cosSqAlpha * (A * A - B * B) / (B * B);
                double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
                double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
                double delta = bigB * sinSigma * (cos2SigmaM + bigB / 4 *
                        (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) - bigB / 6 * cos2SigmaM
                                * (-3 + 4 * sinSigma * sinSigma)
                                * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
                return B * bigA * (sigma - delta);
            }
            lambda = next;
        }
        throw new IllegalArgumentException("WGS84 inverse did not converge");
    }
}
