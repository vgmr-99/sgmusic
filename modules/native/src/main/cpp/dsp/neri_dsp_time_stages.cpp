#include "dsp/neri_dsp_stages.h"

namespace neri::dsp {
namespace {

constexpr float kDelayMaxTimeMs = 1000.0f;
constexpr float kDelayMinTimeMs = 10.0f;
constexpr float kDelayMaxFeedback = 0.85f;
// 改延迟时间时按 80 ms 的时间常数滑动，听感上是磁带机的拖尾而不是台阶
constexpr double kDelayGlideSeconds = 0.08;
constexpr double kDelayDampingHz = 4500.0;

// ITU-R BS.1770-4 K 加权：预滤波高架 + RLB 高通
constexpr double kKWeightShelfHz = 1681.974450955533;
constexpr double kKWeightShelfGainDb = 3.999843853973347;
constexpr double kKWeightShelfQ = 0.7071752369554196;
constexpr double kRlbHighPassHz = 38.13547087602444;
constexpr double kRlbHighPassQ = 0.5003270373238773;
constexpr double kLoudnessOffset = -0.691;
constexpr double kAbsoluteGateLufs = -70.0;
constexpr double kRelativeGateLu = 10.0;
constexpr double kLoudnessSmoothingSeconds = 1.0;

BiquadCoefficients designKWeightShelf(double sampleRate) {
    const double f0 = std::min(kKWeightShelfHz, sampleRate * 0.45);
    const double k = std::tan(kPi * f0 / sampleRate);
    const double vh = std::pow(10.0, kKWeightShelfGainDb / 20.0);
    const double vb = std::pow(vh, 0.49968377);
    const double a0 = 1.0 + k / kKWeightShelfQ + k * k;
    BiquadCoefficients c;
    c.b0 = (vh + vb * k / kKWeightShelfQ + k * k) / a0;
    c.b1 = 2.0 * (k * k - vh) / a0;
    c.b2 = (vh - vb * k / kKWeightShelfQ + k * k) / a0;
    c.a1 = 2.0 * (k * k - 1.0) / a0;
    c.a2 = (1.0 - k / kKWeightShelfQ + k * k) / a0;
    return c;
}

BiquadCoefficients designRlbHighPass(double sampleRate) {
    const double f0 = std::min(kRlbHighPassHz, sampleRate * 0.45);
    const double k = std::tan(kPi * f0 / sampleRate);
    const double a0 = 1.0 + k / kRlbHighPassQ + k * k;
    BiquadCoefficients c;
    c.b0 = 1.0 / a0;
    c.b1 = -2.0 / a0;
    c.b2 = 1.0 / a0;
    c.a1 = 2.0 * (k * k - 1.0) / a0;
    c.a2 = (1.0 - k / kRlbHighPassQ + k * k) / a0;
    return c;
}

float interpolate(float start, float end, int index, int frames) {
    return start + (end - start) * static_cast<float>(index + 1) / static_cast<float>(std::max(frames, 1));
}

} // namespace

void DelayStage::configure(const StageContext& context) {
    context_ = context;
    const double sampleRate = context.sampleRate > 0.0 ? context.sampleRate : 48000.0;
    feedback_.setCoefficient(context.smoothing);
    mix_.setCoefficient(context.smoothing);
    feedback_.snap(0.0f);
    mix_.snap(0.0f);
    const int maxSamples = static_cast<int>(std::lround(sampleRate * kDelayMaxTimeMs * 0.001)) + 4;
    leftDelay_.resize(maxSamples);
    rightDelay_.resize(maxSamples);
    delayCoefficient_ = static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * kDelayGlideSeconds)));
    dampingCoefficient_ = onePoleCoefficient(sampleRate, kDelayDampingHz);
    delaySamples_ = 0.0f;
    delayTargetSamples_ = 0.0f;
    reset();
}

void DelayStage::setTargets(bool enabled, float timeMs, float feedback, float mix) {
    const float clampedTime = clampf(timeMs, kDelayMinTimeMs, kDelayMaxTimeMs);
    const float capacity = static_cast<float>(std::max(2, leftDelay_.capacity() - 1));
    delayTargetSamples_ = clampf(
        clampedTime * static_cast<float>(context_.sampleRate) * 0.001f,
        1.0f,
        capacity
    );
    feedback_.setTarget(clampf(feedback, 0.0f, kDelayMaxFeedback));
    // 关掉延迟时湿声目标归零，湿声淡出后整段直接旁路
    mix_.setTarget(enabled ? clampf(mix, 0.0f, 1.0f) : 0.0f);
}

void DelayStage::snapToTargets() {
    feedback_.snap(feedback_.target());
    mix_.snap(mix_.target());
    delaySamples_ = delayTargetSamples_;
}

void DelayStage::reset() {
    leftDelay_.reset();
    rightDelay_.reset();
    dampedLeft_ = 0.0f;
    dampedRight_ = 0.0f;
    running_ = false;
}

bool DelayStage::active() const {
    return mix_.target() > 0.0f || mix_.value() > 0.0f;
}

float DelayStage::readInterpolated(const DelayLine& line, float delaySamples) const {
    const float capacity = static_cast<float>(std::max(2, line.capacity() - 1));
    const float clamped = clampf(delaySamples, 1.0f, capacity);
    const float whole = std::floor(clamped);
    const float fraction = clamped - whole;
    const float near = line.read(static_cast<int>(whole));
    const float far = line.read(static_cast<int>(whole) + 1);
    return near + (far - near) * fraction;
}

void DelayStage::process(float* left, float* right, int frames) {
    if (!active()) {
        running_ = false;
        return;
    }
    if (!running_) {
        // 重新打开时清掉上一次残留的回声，避免听到无关的旧内容
        leftDelay_.reset();
        rightDelay_.reset();
        dampedLeft_ = 0.0f;
        dampedRight_ = 0.0f;
        delaySamples_ = delayTargetSamples_;
        running_ = true;
    }
    const float mixStart = mix_.value();
    mix_.advance();
    const float mixEnd = mix_.value();
    const float feedbackStart = feedback_.value();
    feedback_.advance();
    const float feedbackEnd = feedback_.value();
    for (int i = 0; i < frames; ++i) {
        delaySamples_ += (delayTargetSamples_ - delaySamples_) * delayCoefficient_;
        const float wetLeft = readInterpolated(leftDelay_, delaySamples_);
        const float wetRight = readInterpolated(rightDelay_, delaySamples_);
        const float mixValue = interpolate(mixStart, mixEnd, i, frames);
        const float feedbackValue = interpolate(feedbackStart, feedbackEnd, i, frames);
        // 反馈回路加一级低通，避免多次回声把高频堆起来
        dampedLeft_ = dampingCoefficient_ * dampedLeft_ + (1.0f - dampingCoefficient_) * wetLeft;
        dampedRight_ = dampingCoefficient_ * dampedRight_ + (1.0f - dampingCoefficient_) * wetRight;
        leftDelay_.push(left[i] + feedbackValue * dampedLeft_);
        rightDelay_.push(right[i] + feedbackValue * dampedRight_);
        // 干声不进延迟线，整条链路仍然是零延迟
        left[i] += mixValue * wetLeft;
        right[i] += mixValue * wetRight;
    }
}

void LoudnessNormalizationStage::configure(const StageContext& context) {
    context_ = context;
    const double sampleRate = context.sampleRate > 0.0 ? context.sampleRate : 48000.0;
    hopSamples_ = std::max(1, static_cast<int>(std::lround(sampleRate * 0.1)));
    gainCoefficient_ = static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * kLoudnessSmoothingSeconds)));
    designFilters();
    reset();
}

void LoudnessNormalizationStage::designFilters() {
    const double sampleRate = context_.sampleRate > 0.0 ? context_.sampleRate : 48000.0;
    shelf_ = designKWeightShelf(sampleRate);
    highPass_ = designRlbHighPass(sampleRate);
    shelfLeft_.reset();
    shelfRight_.reset();
    highPassLeft_.reset();
    highPassRight_.reset();
}

void LoudnessNormalizationStage::setTargets(bool enabled, float targetLufs, float maxGainDb) {
    enabled_ = enabled;
    targetLufs_ = clampf(targetLufs, -24.0f, -9.0f);
    maxGainDb_ = clampf(maxGainDb, 0.0f, 18.0f);
}

void LoudnessNormalizationStage::snapToTargets() {
    gain_ = enabled_ ? targetGain_ : 1.0f;
}

void LoudnessNormalizationStage::reset() {
    shelfLeft_.reset();
    shelfRight_.reset();
    highPassLeft_.reset();
    highPassRight_.reset();
    blocks_.fill(0.0);
    blockIndex_ = 0;
    blockFill_ = 0;
    blockSum_ = 0.0;
    blockSamples_ = 0;
    measuredLufs_ = -70.0f;
    targetGain_ = 1.0f;
    gain_ = 1.0f;
}

bool LoudnessNormalizationStage::active() const {
    return enabled_ || std::fabs(gain_ - 1.0f) > 1.0e-4f;
}

void LoudnessNormalizationStage::updateMeasurement() {
    if (blockFill_ == 0) {
        return;
    }
    // 绝对门限：低于 -70 LUFS 的块不参与统计
    double absoluteSum = 0.0;
    int absoluteCount = 0;
    for (int i = 0; i < blockFill_; ++i) {
        const double energy = blocks_[static_cast<size_t>(i)];
        if (energy <= 0.0) {
            continue;
        }
        const double lufs = kLoudnessOffset + 10.0 * std::log10(energy);
        if (lufs <= kAbsoluteGateLufs) {
            continue;
        }
        absoluteSum += energy;
        ++absoluteCount;
    }
    if (absoluteCount == 0) {
        // 整段都是静音时保持上一次的测量，避免噪声被拉起来
        return;
    }
    // 相对门限：比预估值低 10 LU 的块算作静音段落
    const double preliminary = kLoudnessOffset + 10.0 * std::log10(absoluteSum / absoluteCount);
    const double relativeThreshold = preliminary - kRelativeGateLu;
    double gatedSum = 0.0;
    int gatedCount = 0;
    for (int i = 0; i < blockFill_; ++i) {
        const double energy = blocks_[static_cast<size_t>(i)];
        if (energy <= 0.0) {
            continue;
        }
        const double lufs = kLoudnessOffset + 10.0 * std::log10(energy);
        if (lufs <= relativeThreshold) {
            continue;
        }
        gatedSum += energy;
        ++gatedCount;
    }
    if (gatedCount == 0) {
        return;
    }
    measuredLufs_ = static_cast<float>(kLoudnessOffset + 10.0 * std::log10(gatedSum / gatedCount));
    const float gainDb = clampf(targetLufs_ - measuredLufs_, -maxGainDb_, maxGainDb_);
    targetGain_ = dbToGain(gainDb);
}

void LoudnessNormalizationStage::process(float* left, float* right, int frames) {
    if (!active()) {
        return;
    }
    if (enabled_) {
        for (int i = 0; i < frames; ++i) {
            const float weightedLeft = highPassLeft_.process(highPass_, shelfLeft_.process(shelf_, left[i]));
            const float weightedRight = highPassRight_.process(highPass_, shelfRight_.process(shelf_, right[i]));
            blockSum_ += static_cast<double>(weightedLeft) * weightedLeft +
                static_cast<double>(weightedRight) * weightedRight;
            ++blockSamples_;
            if (blockSamples_ >= hopSamples_) {
                blocks_[static_cast<size_t>(blockIndex_)] =
                    blockSum_ / (2.0 * static_cast<double>(blockSamples_));
                blockIndex_ = (blockIndex_ + 1) % kBlockCount;
                if (blockFill_ < kBlockCount) {
                    ++blockFill_;
                }
                blockSum_ = 0.0;
                blockSamples_ = 0;
                updateMeasurement();
            }
        }
    }
    const float gainTarget = enabled_ ? targetGain_ : 1.0f;
    for (int i = 0; i < frames; ++i) {
        gain_ += (gainTarget - gain_) * gainCoefficient_;
        left[i] *= gain_;
        right[i] *= gain_;
    }
}

} // namespace neri::dsp
