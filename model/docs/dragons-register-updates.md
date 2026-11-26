# Canopy — A4 Dragons Register Updates

**Original model:** A2 Team Canopy, Section 6 — Here Be Dragons in Our Subsystem.

**Affected tickets:** MRD-241 and MRD-239.

## Entry 5 — Lack of Validation Harness

### Previous Evidence (A2)

MRD-231 identified the absence of a validation harness capable of checking whether a replacement model implementation reproduces historical model outputs.

### Updated Evidence (A4)

MRD-241 introduces:

- Pinned numerical input, output and trace fixtures.
- Nitrogen-mineralisation kernel characterization tests.
- Automated numerical trace validation.
- Kernel replay through `model/trace_replay.f90`.
- Tolerance-aware comparison through `model/validate_trace.py`.
- Integration with GitHub CI.

MRD-239 further extends regression coverage to nutrient uptake and unit conversion.

Both validation suites can be executed using:

`make -C model test`

### Updated Risk

The risk of undetected numerical regressions has been reduced but not eliminated. The existing harness validates selected historical behaviours and the current Fortran kernel.

A future replacement implementation must still demonstrate numerical parity. The reference fixtures do not establish correctness across every possible model input.

### Status

Partially mitigated by MRD-241 and MRD-239. The MRD-231 numerical-kernel port remains outstanding.

## Other Models and Contracts

The remaining A2 dragons-register entries are not directly invalidated by Canopy's A4 changes.

Neither ticket changes the external interface contracts, production architecture, crop-development state transitions or GROWTH activity flow. Therefore, no UML diagram modifications are required.

## Release References

- MRD-241 characterization: `255e657`
- MRD-241 implementation: `34e30a4`
- MRD-239 characterization: `21f4209`
- MRD-239 implementation: `c364945`
- Release PR: #54
- Release tag: `a4-canopy`
- Release commit: `53c571cd`