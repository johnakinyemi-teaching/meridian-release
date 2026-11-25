! ======================================================================
!  TRACE_REPLAY - Standalone MRD-231 kernel replay driver for MRD-241
!
!  Reads parsed NMINK input records from stdin:
!    <doy> <nlay> <tfac> <wfac> <rate0> <orgn(1)> ... <orgn(nlay)>
!  Calls nmin_kernel and emits the 4-line NMINK trace record to stdout
!  via nmin_trace so validate_trace.py can verify the kernel against
!  the MERIDIAN_TRACE.REF fixture.
! ======================================================================
program trace_replay
   use nbal_kernel, only : nmin_kernel, nmin_trace, KN_MAXLAY
   implicit none

   integer :: doy, nlay, ios, k
   real    :: tfac, wfac, rate0, total
   real    :: orgn(KN_MAXLAY), minout(KN_MAXLAY)

   write (*, '(A)') 'MERIDIAN NMIN KERNEL TRACE  (MRD-241 replay)'

   do
      read (*, *, iostat=ios) doy, nlay, tfac, wfac, rate0, &
                              (orgn(k), k = 1, KN_MAXLAY)
      if (ios .ne. 0) exit
      if (nlay .lt. 1 .or. nlay .gt. KN_MAXLAY) then
         write (*, '(A, I0)') 'TRACE_REPLAY: invalid nlay = ', nlay
         stop 2
      end if

      call nmin_kernel(nlay, orgn(1:nlay), tfac, wfac, rate0, &
                       minout(1:nlay), total)
      call nmin_trace(6, doy, nlay, orgn(1:nlay), tfac, wfac, rate0, &
                      minout(1:nlay), total)
   end do
end program trace_replay
