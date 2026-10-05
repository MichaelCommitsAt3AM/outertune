/*************************************************************************

  kiss_fft-backed replacement for libKeyFinder's FFT adapter.

  Upstream libKeyFinder ships src/fftadapter.cpp implemented against FFTW3
  (fftw_plan_dft_r2c_1d / fftw_plan_dft_c2r_1d). FFTW is not vendored for the
  OuterTune Android build, so this drop-in replacement implements the same
  FftAdapter / InverseFftAdapter interface on top of kiss_fftr (vendored in
  app/src/main/cpp/kiss_fft).

  Behaviour is kept identical to the FFTW version:
    * real -> complex forward transform, complex -> real inverse transform
    * output bins are indexed [0, frameSize); kiss fills [0, frameSize/2],
      the remaining bins read back as zero (matches the memset in the
      original FFTW adapter)
    * getOutputMagnitude() == hypot(real, imaginary)
    * InverseFftAdapter::getOutput() divides by frameSize to normalise
    * out-of-range access throws KeyFinder::Exception

  libKeyFinder is GPLv3; this file is distributed under the same terms.

*************************************************************************/

#include "fftadapter.h"
#include "exception.h"

#include <cmath>
#include <mutex>
#include <sstream>
#include <vector>

#include "kiss_fftr.h"

namespace KeyFinder {

  // Retained for source compatibility with the upstream file; kiss_fftr_alloc
  // has no global planner state that needs guarding, but other translation
  // units historically referenced this symbol.
  std::mutex fftwPlanMutex;

  // ================================= FORWARD =================================

  class FftAdapterPrivate {
  public:
    explicit FftAdapterPrivate(unsigned int n) :
        inputReal(n, 0.0),
        outputComplex(n, kiss_fft_cpx{0.0, 0.0}),
        cfg(kiss_fftr_alloc(static_cast<int>(n), 0, nullptr, nullptr)) { }
    ~FftAdapterPrivate() { kiss_fftr_free(cfg); }
    std::vector<kiss_fft_scalar> inputReal;
    std::vector<kiss_fft_cpx> outputComplex;
    kiss_fftr_cfg cfg;
  };

  FftAdapter::FftAdapter(unsigned int inFrameSize) {
    frameSize = inFrameSize;
    priv = new FftAdapterPrivate(frameSize);
  }

  FftAdapter::~FftAdapter() {
    delete priv;
  }

  unsigned int FftAdapter::getFrameSize() const {
    return frameSize;
  }

  void FftAdapter::setInput(unsigned int i, double real) {
    if (i >= frameSize) {
      std::ostringstream ss;
      ss << "Cannot set out-of-bounds sample (" << i << "/" << frameSize << ")";
      throw Exception(ss.str().c_str());
    }
    if (!std::isfinite(real)) {
      throw Exception("Cannot set sample to NaN");
    }
    priv->inputReal[i] = real;
  }

  double FftAdapter::getOutputReal(unsigned int i) const {
    if (i >= frameSize) {
      std::ostringstream ss;
      ss << "Cannot get out-of-bounds sample (" << i << "/" << frameSize << ")";
      throw Exception(ss.str().c_str());
    }
    return priv->outputComplex[i].r;
  }

  double FftAdapter::getOutputImaginary(unsigned int i) const {
    if (i >= frameSize) {
      std::ostringstream ss;
      ss << "Cannot get out-of-bounds sample (" << i << "/" << frameSize << ")";
      throw Exception(ss.str().c_str());
    }
    return priv->outputComplex[i].i;
  }

  double FftAdapter::getOutputMagnitude(unsigned int i) const {
    const double re = getOutputReal(i);
    const double im = getOutputImaginary(i);
    return std::sqrt(re * re + im * im);
  }

  void FftAdapter::execute() {
    kiss_fftr(priv->cfg, priv->inputReal.data(), priv->outputComplex.data());
  }

  // ================================= INVERSE =================================

  class InverseFftAdapterPrivate {
  public:
    explicit InverseFftAdapterPrivate(unsigned int n) :
        inputComplex(n, kiss_fft_cpx{0.0, 0.0}),
        outputReal(n, 0.0),
        cfg(kiss_fftr_alloc(static_cast<int>(n), 1, nullptr, nullptr)) { }
    ~InverseFftAdapterPrivate() { kiss_fftr_free(cfg); }
    std::vector<kiss_fft_cpx> inputComplex;
    std::vector<kiss_fft_scalar> outputReal;
    kiss_fftr_cfg cfg;
  };

  InverseFftAdapter::InverseFftAdapter(unsigned int inFrameSize) {
    frameSize = inFrameSize;
    priv = new InverseFftAdapterPrivate(frameSize);
  }

  InverseFftAdapter::~InverseFftAdapter() {
    delete priv;
  }

  unsigned int InverseFftAdapter::getFrameSize() const {
    return frameSize;
  }

  void InverseFftAdapter::setInput(unsigned int i, double real, double imag) {
    if (i >= frameSize) {
      std::ostringstream ss;
      ss << "Cannot set out-of-bounds sample (" << i << "/" << frameSize << ")";
      throw Exception(ss.str().c_str());
    }
    if (!std::isfinite(real) || !std::isfinite(imag)) {
      throw Exception("Cannot set sample to NaN");
    }
    priv->inputComplex[i].r = real;
    priv->inputComplex[i].i = imag;
  }

  double InverseFftAdapter::getOutput(unsigned int i) const {
    if (i >= frameSize) {
      std::ostringstream ss;
      ss << "Cannot get out-of-bounds sample (" << i << "/" << frameSize << ")";
      throw Exception(ss.str().c_str());
    }
    // divide by frameSize to normalise, matching the FFTW-based implementation
    return priv->outputReal[i] / frameSize;
  }

  void InverseFftAdapter::execute() {
    kiss_fftri(priv->cfg, priv->inputComplex.data(), priv->outputReal.data());
  }

}
