program test_water_stress
    use water_stress, only: calculate_stress
    implicit none

    real :: stress
    integer :: failures

    failures = 0

    stress = calculate_stress(20.0, 0.2, 500.0)
    call check("500 mm rooting depth", stress, 0.4, failures)

    stress = calculate_stress(20.0, 0.2, 1000.0)
    call check("1000 mm rooting depth", stress, 0.2, failures)

    stress = calculate_stress(150.0, 0.2, 1000.0)
    call check("Above stress threshold", stress, 1.0, failures)

    stress = calculate_stress(20.0, 0.0, 500.0)
    call check("Zero capacity fallback", stress, 1.0, failures)

    if (failures == 0) then
        print *, "PASS: All water stress tests passed."
    else
        print *, "FAIL:", failures, "test(s) failed."
        stop 1
    end if

contains

    subroutine check(test_name, actual, expected, failures)
        character(len=*), intent(in) :: test_name
        real, intent(in) :: actual, expected
        integer, intent(inout) :: failures

        if (abs(actual - expected) > 0.0001) then
            print *, "FAIL: ", test_name, " expected ", expected, &
                     " but got ", actual
            failures = failures + 1
        else
            print *, "PASS: ", test_name
        end if
    end subroutine check

end program test_water_stress
