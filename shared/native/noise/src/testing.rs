pub struct Coordinates {
    pub state: u64,
}

impl Coordinates {
    pub fn next(&mut self) -> u64 {
        self.state ^= self.state << 13;
        self.state ^= self.state >> 7;
        self.state ^= self.state << 17;
        self.state
    }

    pub fn coordinate(&mut self) -> f64 {
        let bits: u64 = self.next();
        let unit: f64 = (bits >> 11) as f64 / (1u64 << 53) as f64;
        match bits % 11 {
            0 | 1 => unit * 80.0 - 40.0,
            2 | 3 => ((bits >> 20) % 600_000) as f64 - 300_000.0,
            4 | 5 => unit * 600_000.0 - 300_000.0,
            6 | 7 => [0.0, -0.0, 0.5, -0.5, 1.0e-9, -299_999.995, 16.0][(bits >> 32) as usize % 7],
            8 => (unit * 2.0 - 1.0) * 4.0e12,
            9 if (bits >> 40) & 3 == 0 => [
                1.0e17,
                -3.0e15,
                1_125_899_906_842_624.0,
                -1_125_899_906_842_623.5,
                f64::NAN,
                f64::INFINITY,
                f64::NEG_INFINITY,
                -1.0e300,
            ][(bits >> 32) as usize % 8],
            9 => unit * 80.0 - 40.0,
            _ => (unit * 64.0).floor() * 0.25 - 8.0,
        }
    }

    pub fn fill(&mut self, count: usize) -> Vec<f64> {
        (0..count).map(|_| self.coordinate()).collect()
    }
}

pub fn assert_bits(name: &str, expected: &[f64], actual: &[f64]) {
    for (index, (left, right)) in expected.iter().zip(actual).enumerate() {
        if left.is_nan() && right.is_nan() {
            continue;
        }
        assert_eq!(left.to_bits(), right.to_bits(), "{name} differs at {index}: {left} vs {right}");
    }
}
