! ======================================================================
!  TEST_KERNEL - Characterization test suite for NBAL_KERNEL (MRD-241)
!  Author: Liene (Canopy Team - Task 1 Safety Net)
!
!  Pins current behaviour of nmin_kernel and nmin_trace before any
!  production changes for MRD-241 or MRD-231.
! ======================================================================
program test_kernel
   use nbal_kernel, only : nmin_kernel, nmin_trace, KN_MAXLAY
   implicit none

   integer :: passed, failed
   passed = 0
   failed = 0

   write (*, '(A)') '=================================================='
   write (*, '(A)') ' Running NBAL_KERNEL Characterization Tests (Liene)'
   write (*, '(A)') '=================================================='

   call test_standard_kernel(passed, failed)
   call test_temperature_clamping(passed, failed)
   call test_moisture_clamping(passed, failed)
   call test_rate_clamping(passed, failed)
   call test_stock_clamping(passed, failed)
   call test_stock_ceiling(passed, failed)
   call test_max_layers(passed, failed)
   call test_trace_format(passed, failed)

   write (*, '(A)') '--------------------------------------------------'
   write (*, '(A, I3, A, I3, A)') ' Test results: ', passed, ' passed, ', failed, ' failed.'
   write (*, '(A)') '--------------------------------------------------'

   if (failed .gt. 0) then
      write (*, '(A)') 'FAILURE: One or more characterization tests failed!'
      stop 1
   else
      write (*, '(A)') 'SUCCESS: All NBAL_KERNEL characterization tests passed.'
   end if

contains

   ! -------------------------------------------------------------------
   ! Test 1: Standard multi-layer mineralisation calculation
   ! -------------------------------------------------------------------
   subroutine test_standard_kernel(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 5
      real :: orgn(NLAY)
      real :: minout(NLAY)
      real :: tfac, wfac, rate0, total
      real :: mult, expected_total
      integer :: k
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      orgn = (/ 800.0, 600.0, 500.0, 500.0, 500.0 /)
      tfac  = 0.5
      wfac  = 0.8
      rate0 = 0.001

      call nmin_kernel(NLAY, orgn, tfac, wfac, rate0, minout, total)

      mult = rate0 * tfac * wfac  ! 0.0004
      ok = .true.
      expected_total = 0.0
      do k = 1, NLAY
         if (abs(minout(k) - mult * orgn(k)) .gt. EPS) ok = .false.
         expected_total = expected_total + mult * orgn(k)
      end do
      if (abs(total - expected_total) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_standard_kernel (5 layers, standard rates)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_standard_kernel output diverged'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_standard_kernel

   ! -------------------------------------------------------------------
   ! Test 2: Temperature factor clamping (tfac < 0 -> 0)
   ! -------------------------------------------------------------------
   subroutine test_temperature_clamping(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 3
      real :: orgn(NLAY)
      real :: minout(NLAY)
      real :: total
      logical :: ok
      real, parameter :: EPS = 1.0e-6

      orgn = (/ 500.0, 400.0, 300.0 /)
      ! Negative temperature multiplier
      call nmin_kernel(NLAY, orgn, -0.5, 0.8, 0.001, minout, total)

      ok = (abs(total) .le. EPS) .and. all(abs(minout) .le. EPS)

      if (ok) then
         write (*, '(A)') '  PASS: test_temperature_clamping (tfac < 0 clamped to 0)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_temperature_clamping failed to clamp'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_temperature_clamping

   ! -------------------------------------------------------------------
   ! Test 3: Moisture factor clamping (wfac < 0 -> 0, wfac > 1 -> 1)
   ! -------------------------------------------------------------------
   subroutine test_moisture_clamping(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 3
      real :: orgn(NLAY)
      real :: minout1(NLAY), minout2(NLAY)
      real :: total1, total2
      logical :: ok
      real, parameter :: EPS = 1.0e-6

      orgn = (/ 500.0, 400.0, 300.0 /)

      ! Negative wfac clamped to 0
      call nmin_kernel(NLAY, orgn, 0.6, -0.3, 0.001, minout1, total1)
      ok = (abs(total1) .le. EPS) .and. all(abs(minout1) .le. EPS)

      ! wfac > 1.0 clamped to 1.0: output with wfac=1.5 must equal wfac=1.0
      call nmin_kernel(NLAY, orgn, 0.6, 1.5, 0.001, minout1, total1)
      call nmin_kernel(NLAY, orgn, 0.6, 1.0, 0.001, minout2, total2)

      if (abs(total1 - total2) .gt. EPS .or. any(abs(minout1 - minout2) .gt. EPS)) then
         ok = .false.
      end if

      if (ok) then
         write (*, '(A)') '  PASS: test_moisture_clamping (wfac bounded to [0.0, 1.0])'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_moisture_clamping failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_moisture_clamping

   ! -------------------------------------------------------------------
   ! Test 4: Rate clamping (rate0 < 0 -> 0)
   ! -------------------------------------------------------------------
   subroutine test_rate_clamping(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 3
      real :: orgn(NLAY)
      real :: minout(NLAY)
      real :: total
      logical :: ok
      real, parameter :: EPS = 1.0e-6

      orgn = (/ 500.0, 400.0, 300.0 /)
      call nmin_kernel(NLAY, orgn, 0.6, 0.5, -0.002, minout, total)

      ok = (abs(total) .le. EPS) .and. all(abs(minout) .le. EPS)

      if (ok) then
         write (*, '(A)') '  PASS: test_rate_clamping (rate0 < 0 clamped to 0)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_rate_clamping failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_rate_clamping

   ! -------------------------------------------------------------------
   ! Test 5: Stock clamping (orgn(k) < 0 -> 0)
   ! -------------------------------------------------------------------
   subroutine test_stock_clamping(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 2
      real :: orgn(NLAY)
      real :: minout(NLAY)
      real :: total
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      orgn = (/ -100.0, 400.0 /)
      call nmin_kernel(NLAY, orgn, 1.0, 1.0, 0.01, minout, total)

      ok = (abs(minout(1)) .le. EPS) .and. &
           (abs(minout(2) - 4.0) .le. EPS) .and. &
           (abs(total - 4.0) .le. EPS)

      if (ok) then
         write (*, '(A)') '  PASS: test_stock_clamping (negative orgn clamped to 0)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_stock_clamping failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_stock_clamping

   ! -------------------------------------------------------------------
   ! Test 6: Stock ceiling (mineralisation cannot exceed layer stock)
   ! -------------------------------------------------------------------
   subroutine test_stock_ceiling(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 2
      real :: orgn(NLAY)
      real :: minout(NLAY)
      real :: total
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      orgn = (/ 100.0, 250.0 /)
      ! rate0 * tfac * wfac = 2.0 * 1.0 * 1.0 = 2.0 (would yield 200.0 and 500.0)
      call nmin_kernel(NLAY, orgn, 1.0, 1.0, 2.0, minout, total)

      ok = (abs(minout(1) - 100.0) .le. EPS) .and. &
           (abs(minout(2) - 250.0) .le. EPS) .and. &
           (abs(total - 350.0) .le. EPS)

      if (ok) then
         write (*, '(A)') '  PASS: test_stock_ceiling (minout capped at stock)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_stock_ceiling failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_stock_ceiling

   ! -------------------------------------------------------------------
   ! Test 7: Maximum layer support (KN_MAXLAY = 8)
   ! -------------------------------------------------------------------
   subroutine test_max_layers(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      real :: orgn(KN_MAXLAY)
      real :: minout(KN_MAXLAY)
      real :: total
      integer :: k
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      do k = 1, KN_MAXLAY
         orgn(k) = real(k) * 100.0
      end do

      call nmin_kernel(KN_MAXLAY, orgn, 1.0, 1.0, 0.001, minout, total)

      ok = .true.
      do k = 1, KN_MAXLAY
         if (abs(minout(k) - 0.001 * orgn(k)) .gt. EPS) ok = .false.
      end do
      ! sum of 100..800 = 3600; 0.001 * 3600 = 3.6
      if (abs(total - 3.6) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_max_layers (KN_MAXLAY = 8 layers)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_max_layers failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_max_layers

   ! -------------------------------------------------------------------
   ! Test 8: Trace emitter record format (nmin_trace)
   ! -------------------------------------------------------------------
   subroutine test_trace_format(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      integer, parameter :: NLAY = 5
      real :: orgn(NLAY), minout(NLAY)
      real :: tfac, wfac, rate0, total
      integer :: scr_unit, doy
      character(len=120) :: line1, line2, line3, line4
      logical :: ok

      orgn   = (/ 823.5602, 617.6702, 549.0402, 514.7252, 494.1361 /)
      minout = (/ 0.238405, 0.178804, 0.158937, 0.149003, 0.143043 /)
      doy    = 100
      tfac   = 0.6261
      wfac   = 0.5778
      rate0  = 0.000800
      total  = 0.868193

      open (newunit=scr_unit, status='scratch')
      call nmin_trace(scr_unit, doy, NLAY, orgn, tfac, wfac, rate0, minout, total)
      rewind (scr_unit)

      read (scr_unit, '(A)') line1
      read (scr_unit, '(A)') line2
      read (scr_unit, '(A)') line3
      read (scr_unit, '(A)') line4
      close (scr_unit)

      ok = .true.
      if (index(line1, 'NMINK DOY= 100 TFAC=  0.6261 WFAC=  0.5778 RATE0=  0.000800 NLAY= 5') .eq. 0) ok = .false.
      if (index(line2, 'ORGN') .eq. 0 .or. index(line2, '823.5602') .eq. 0) ok = .false.
      if (index(line3, 'MINOUT') .eq. 0 .or. index(line3, '0.238405') .eq. 0) ok = .false.
      if (index(line4, 'TOTAL') .eq. 0 .or. index(line4, '0.868193') .eq. 0) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_trace_format (nmin_trace 4-line fixed record)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_trace_format output format mismatch'
         write (*, '(A, A)') '    Line 1: ', trim(line1)
         write (*, '(A, A)') '    Line 2: ', trim(line2)
         write (*, '(A, A)') '    Line 3: ', trim(line3)
         write (*, '(A, A)') '    Line 4: ', trim(line4)
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_trace_format

end program test_kernel

