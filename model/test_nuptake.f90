! ======================================================================
!  TEST_NUPTAKE - Characterization test suite for NUPTAKE / NUTRIENT (MRD-239)
!  Author: Liene (Canopy Team - Task 1 Safety Net)
!
!  Pins current behaviour of nutrient conversion and uptake before any
!  production changes for MRD-239:
!    1. Unit conversion discrepancy between NUTRIENT (NAREA=10.0) and
!       NUPTAKE (GM2_TO_KGHA=100.0, a 10x factor disagreement).
!    2. Stage-dependent nitrogen demand calculations (n_demand_gm2).
!    3. Soil pool drawdown under the current 100.0 factor.
!    4. Supply limitation and premature soil pool depletion.
!    5. Nitrogen stress factor calculation and bounds (n_stress_factor).
!    6. Edge cases: zero demand, negative demand, empty pools.
!    7. Layer-wise root weighting and NH4/NO3 ion partitioning.
! ======================================================================
program test_nuptake
   use soilwat,    only : NLAYERS, ROOT_FRAC
   use nutrient,   only : npool_t, NAREA, NH4_TOTAL0, NO3_TOTAL0, &
                          n_init, n_mineral_total, n_mineral_layer
   use nuptake,    only : n_uptake, n_demand_gm2, n_stress_factor, &
                          NSTR_FLOOR, NCONC_VEG, NCONC_GRAIN, NCONC_LATE
   use phenology,  only : pheno_t, STAGE_EMERGED, STAGE_VEG, &
                          STAGE_GRAINFIL, STAGE_MATURE
   implicit none

   integer :: passed, failed
   passed = 0
   failed = 0

   write (*, '(A)') '=================================================='
   write (*, '(A)') ' Running NUPTAKE / MRD-239 Characterization Tests '
   write (*, '(A)') '=================================================='

   call test_conversion_discrepancy(passed, failed)
   call test_n_demand_stages(passed, failed)
   call test_uptake_pool_drawdown(passed, failed)
   call test_depletion_and_supply(passed, failed)
   call test_n_stress_factor(passed, failed)
   call test_uptake_boundaries(passed, failed)
   call test_layer_partitioning(passed, failed)

   write (*, '(A)') '--------------------------------------------------'
   write (*, '(A, I3, A, I3, A)') ' Test results: ', passed, ' passed, ', failed, ' failed.'
   write (*, '(A)') '--------------------------------------------------'

   if (failed .gt. 0) then
      write (*, '(A)') 'FAILURE: One or more characterization tests failed!'
      stop 1
   else
      write (*, '(A)') 'SUCCESS: All NUPTAKE characterization tests passed.'
   end if

contains

   ! -------------------------------------------------------------------
   ! Test 1: Pinned units & 10x discrepancy contract (MRD-239)
   !
   ! NUTRIENT specifies NAREA = 10.0 (1 g m-2 = 10 kg ha-1).
   ! NUPTAKE currently uses GM2_TO_KGHA = 100.0.
   ! This test pins the current behavior: n_uptake extracts 100.0 kg/ha
   ! for 1.0 g/m2 demand, disagreeing with NAREA by exactly a factor of 10.
   ! -------------------------------------------------------------------
   subroutine test_conversion_discrepancy(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      type(npool_t) :: p
      real :: demand, taken, supplied
      real :: effective_rate, discrepancy
      integer :: k
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      ! Create an ample mineral N pool in all layers (total = 500 kg N ha-1)
      do k = 1, NLAYERS
         p%orgn(k) = 500.0
         p%nh4(k)  = 50.0
         p%no3(k)  = 50.0
      end do

      demand = 1.0   ! 1.0 g N m-2
      call n_uptake(p, demand, taken, supplied)

      effective_rate = taken / demand
      discrepancy    = effective_rate / NAREA

      ok = .true.
      if (abs(NAREA - 10.0) .gt. EPS) ok = .false.
      if (abs(effective_rate - 100.0) .gt. EPS) ok = .false.
      if (abs(discrepancy - 10.0) .gt. EPS) ok = .false.
      if (abs(supplied - 1.0) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_conversion_discrepancy (pinned 10x gap: NAREA=10.0 vs GM2_TO_KGHA=100.0)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_conversion_discrepancy mismatch'
         write (*, '(A, F8.2)') '    NAREA:          ', NAREA
         write (*, '(A, F8.2)') '    Effective rate: ', effective_rate
         write (*, '(A, F8.2)') '    Discrepancy:    ', discrepancy
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_conversion_discrepancy

   ! -------------------------------------------------------------------
   ! Test 2: Phenology-dependent crop N demand (n_demand_gm2)
   ! -------------------------------------------------------------------
   subroutine test_n_demand_stages(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      type(pheno_t) :: ph
      real :: dbiom, dem_veg, dem_grain, dem_mat, dem_neg, dem_zero
      logical :: ok
      real, parameter :: EPS = 1.0e-6

      dbiom = 10.0   ! 10.0 g DM m-2

      ! Vegetative stage
      ph%stage = STAGE_VEG
      dem_veg = n_demand_gm2(dbiom, ph)

      ! Grain fill stage
      ph%stage = STAGE_GRAINFIL
      dem_grain = n_demand_gm2(dbiom, ph)

      ! Mature stage
      ph%stage = STAGE_MATURE
      dem_mat = n_demand_gm2(dbiom, ph)

      ! Negative dry matter increment (clamped to 0)
      ph%stage = STAGE_VEG
      dem_neg = n_demand_gm2(-5.0, ph)

      ! Zero increment
      dem_zero = n_demand_gm2(0.0, ph)

      ok = .true.
      if (abs(dem_veg - dbiom * NCONC_VEG) .gt. EPS) ok = .false.       ! 10 * 0.040 = 0.400
      if (abs(dem_grain - dbiom * NCONC_GRAIN) .gt. EPS) ok = .false.   ! 10 * 0.021 = 0.210
      if (abs(dem_mat - dbiom * NCONC_LATE) .gt. EPS) ok = .false.      ! 10 * 0.013 = 0.130
      if (abs(dem_neg - 0.0) .gt. EPS) ok = .false.
      if (abs(dem_zero - 0.0) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_n_demand_stages (VEG=0.040, GRAINFIL=0.021, MATURE=0.013)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_n_demand_stages failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_n_demand_stages

   ! -------------------------------------------------------------------
   ! Test 3: Soil pool drawdown under current 100.0 factor (ample pool)
   !
   ! Starting profile: 12 kg NH4 + 28 kg NO3 = 40 kg N ha-1 mineral N.
   ! Demand = 0.20 g N m-2.
   ! Under current code: dem_kgha = 0.20 * 100.0 = 20.0 kg N ha-1.
   ! Taken must be 20.0 kg N ha-1, remaining mineral pool must be 20.0.
   ! -------------------------------------------------------------------
   subroutine test_uptake_pool_drawdown(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      type(npool_t) :: p
      real :: demand, taken, supplied, initial_mineral, final_mineral
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      call n_init(p)
      initial_mineral = n_mineral_total(p)   ! 40.0 kg N ha-1

      demand = 0.20   ! 0.20 g N m-2
      call n_uptake(p, demand, taken, supplied)

      final_mineral = n_mineral_total(p)

      ok = .true.
      if (abs(initial_mineral - 40.0) .gt. EPS) ok = .false.
      if (abs(taken - 20.0) .gt. EPS) ok = .false.
      if (abs(supplied - 1.0) .gt. EPS) ok = .false.
      if (abs(final_mineral - 20.0) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_uptake_pool_drawdown (0.20 g/m2 extracts 20.0 kg/ha under 100x)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_uptake_pool_drawdown failed'
         write (*, '(A, F8.3)') '    Taken:         ', taken
         write (*, '(A, F8.3)') '    Supplied:      ', supplied
         write (*, '(A, F8.3)') '    Final mineral: ', final_mineral
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_uptake_pool_drawdown

   ! -------------------------------------------------------------------
   ! Test 4: Current soil depletion and supply limitation under 100.0 factor
   !
   ! Available mineral N = 25.0 kg N ha-1 across 5 layers (5.0 kg each).
   ! Demand = 0.50 g N m-2 -> dem_kgha = 50.0 kg N ha-1 under current code (100x).
   ! Root fraction weighting: ROOT_FRAC = (/0.40, 0.26, 0.17, 0.11, 0.06/).
   ! Layers 1-4 demand exceeds 5.0 kg/ha, so each yields its full 5.0 kg/ha.
   ! Layer 5 demand = 50.0 * 0.06 = 3.0 kg/ha, leaving 2.0 kg/ha remaining.
   ! Total taken = 23.0 kg N ha-1, supplied = 23.0 / 50.0 = 0.460.
   ! Remaining mineral N = 2.0 kg N ha-1.
   ! Furthermore, with demand = 1.0 g N m-2 (dem_kgha = 100.0 kg/ha),
   ! all layers including layer 5 (want=6.0 > 5.0) are completely depleted:
   ! taken = 25.0, supplied = 0.250, remaining = 0.0.
   ! -------------------------------------------------------------------
   subroutine test_depletion_and_supply(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      type(npool_t) :: p
      real :: demand, taken, supplied, remaining_mineral
      integer :: k
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      ! 5 kg mineral N per layer (1.0 NH4 + 4.0 NO3) = 25.0 kg N ha-1
      do k = 1, NLAYERS
         p%orgn(k) = 500.0
         p%nh4(k)  = 1.0
         p%no3(k)  = 4.0
      end do

      demand = 0.50   ! 0.50 g N m-2 -> 50.0 kg N ha-1 under 100x
      call n_uptake(p, demand, taken, supplied)

      remaining_mineral = n_mineral_total(p)

      ok = .true.
      ! Under current single-pass draw:
      ! Layer 1: want=20.0, take=5.0
      ! Layer 2: want=13.0, take=5.0
      ! Layer 3: want=8.5,  take=5.0
      ! Layer 4: want=5.5,  take=5.0
      ! Layer 5: want=3.0,  take=3.0 (leaving 2.0)
      if (abs(taken - 23.0) .gt. EPS) ok = .false.
      if (abs(supplied - 0.460) .gt. EPS) ok = .false.
      if (abs(remaining_mineral - 2.0) .gt. EPS) ok = .false.

      ! Part B: Complete exhaustion of all layers with higher demand (1.0 g N m-2)
      do k = 1, NLAYERS
         p%orgn(k) = 500.0
         p%nh4(k)  = 1.0
         p%no3(k)  = 4.0
      end do
      call n_uptake(p, 1.0, taken, supplied)
      if (abs(taken - 25.0) .gt. EPS) ok = .false.
      if (abs(supplied - 0.250) .gt. EPS) ok = .false.
      if (abs(n_mineral_total(p) - 0.0) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_depletion_and_supply (proportional layer cap & complete pool depletion)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_depletion_and_supply failed'
         write (*, '(A, F8.3)') '    Taken:             ', taken
         write (*, '(A, F8.3)') '    Supplied:          ', supplied
         write (*, '(A, F8.3)') '    Remaining mineral: ', remaining_mineral
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_depletion_and_supply

   ! -------------------------------------------------------------------
   ! Test 5: Nitrogen stress factor calculation and bounds
   !
   ! n_stress_factor = NSTR_FLOOR + (1.0 - NSTR_FLOOR) * supplied
   ! NSTR_FLOOR = 0.35.
   ! -------------------------------------------------------------------
   subroutine test_n_stress_factor(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      real :: str_full, str_half, str_zero, str_neg, str_over
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      str_full = n_stress_factor(1.0)
      str_half = n_stress_factor(0.50)
      str_zero = n_stress_factor(0.0)
      str_neg  = n_stress_factor(-0.25)
      str_over = n_stress_factor(1.50)

      ok = .true.
      if (abs(str_full - 1.0) .gt. EPS) ok = .false.
      ! 0.35 + 0.65 * 0.5 = 0.675
      if (abs(str_half - 0.675) .gt. EPS) ok = .false.
      if (abs(str_zero - NSTR_FLOOR) .gt. EPS) ok = .false.
      if (abs(str_neg  - NSTR_FLOOR) .gt. EPS) ok = .false.
      if (abs(str_over - 1.0) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_n_stress_factor (floor=0.35, linear response, bounded [0.35, 1.0])'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_n_stress_factor failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_n_stress_factor

   ! -------------------------------------------------------------------
   ! Test 6: Boundary conditions (zero demand, negative demand, empty pool)
   ! -------------------------------------------------------------------
   subroutine test_uptake_boundaries(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      type(npool_t) :: p
      real :: taken, supplied
      integer :: k
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      ! Case A: Zero demand on initialized pool
      call n_init(p)
      call n_uptake(p, 0.0, taken, supplied)
      ok = (abs(taken) .le. EPS) .and. (abs(supplied - 1.0) .le. EPS) .and. &
           (abs(n_mineral_total(p) - 40.0) .le. EPS)

      ! Case B: Negative demand (clamped)
      call n_uptake(p, -2.5, taken, supplied)
      if (abs(taken) .gt. EPS .or. abs(supplied - 1.0) .gt. EPS .or. &
          abs(n_mineral_total(p) - 40.0) .gt. EPS) ok = .false.

      ! Case C: Empty mineral pool with positive demand
      do k = 1, NLAYERS
         p%nh4(k) = 0.0
         p%no3(k) = 0.0
      end do
      call n_uptake(p, 0.10, taken, supplied)
      if (abs(taken) .gt. EPS .or. abs(supplied - 0.0) .gt. EPS) ok = .false.

      if (ok) then
         write (*, '(A)') '  PASS: test_uptake_boundaries (zero/neg demand handled, empty pool yields supplied=0)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_uptake_boundaries failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_uptake_boundaries

   ! -------------------------------------------------------------------
   ! Test 7: Layer-wise root weighting and NH4/NO3 split
   !
   ! Uniform available mineral N in each layer (100.0 kg/ha: 20 NH4 + 80 NO3).
   ! Weight = ROOT_FRAC(k) * 100.0 -> layer fraction = ROOT_FRAC(k).
   ! Demand = 0.10 g N m-2 -> dem_kgha = 10.0 kg N ha-1 under current code.
   ! Layer k draw = 10.0 * ROOT_FRAC(k), split 20% NH4 and 80% NO3.
   ! -------------------------------------------------------------------
   subroutine test_layer_partitioning(pass_cnt, fail_cnt)
      integer, intent(inout) :: pass_cnt, fail_cnt
      type(npool_t) :: p
      real :: demand, taken, supplied
      real :: want_k, exp_nh4, exp_no3
      integer :: k
      logical :: ok
      real, parameter :: EPS = 1.0e-5

      do k = 1, NLAYERS
         p%orgn(k) = 500.0
         p%nh4(k)  = 20.0
         p%no3(k)  = 80.0
      end do

      demand = 0.10   ! 0.10 g N m-2 -> 10.0 kg N ha-1
      call n_uptake(p, demand, taken, supplied)

      ok = .true.
      if (abs(taken - 10.0) .gt. EPS .or. abs(supplied - 1.0) .gt. EPS) ok = .false.

      do k = 1, NLAYERS
         want_k  = 10.0 * ROOT_FRAC(k)
         exp_nh4 = 20.0 - want_k * 0.20
         exp_no3 = 80.0 - want_k * 0.80
         if (abs(p%nh4(k) - exp_nh4) .gt. EPS) ok = .false.
         if (abs(p%no3(k) - exp_no3) .gt. EPS) ok = .false.
      end do

      if (ok) then
         write (*, '(A)') '  PASS: test_layer_partitioning (root-weighted draw across layers + proportional ion split)'
         pass_cnt = pass_cnt + 1
      else
         write (*, '(A)') '  FAIL: test_layer_partitioning failed'
         fail_cnt = fail_cnt + 1
      end if
   end subroutine test_layer_partitioning

end program test_nuptake
